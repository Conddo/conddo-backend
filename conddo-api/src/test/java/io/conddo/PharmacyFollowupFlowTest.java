package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.api.pharmacy.PharmacyFollowupMissedScheduler;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Roadmap Beta 2 — Follow-up Workflow + the feature flag
 * gate the FE depends on.
 *
 * <ul>
 *   <li>Without the flag granted → 403 FEATURE_NOT_ENABLED with the
 *       featureKey field hint.</li>
 *   <li>With the flag granted → create, list, due-today,
 *       complete, cancel all work end-to-end.</li>
 *   <li>Missed-sweep cron flips PENDING + >48h old to MISSED;
 *       backdated cutoff so the test doesn't wait wall-clock.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyFollowupFlowTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";
    private static final String PASSWORD = "password123";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conddo")
            .withUsername("conddo_owner")
            .withPassword("owner_password")
            .withInitScript("db/test-init.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_role", () -> APP_USER);
        registry.add("conddo.security.auth.cookie-secure", () -> "false");
        registry.add("conddo.security.cors.allowed-origins", () -> "https://app.conddo.io");
        registry.add("conddo.signup.seed-sample-data", () -> "false");
        registry.add("conddo.billing.expiry-cron", () -> "0 0 0 1 1 ?");
        registry.add("conddo.pharmacy.reminder-cron", () -> "0 0 0 1 1 ?");
        registry.add("conddo.pharmacy.discount-expiry-cron", () -> "0 0 0 1 1 ?");
        registry.add("conddo.pharmacy.followup-missed-cron", () -> "0 0 0 1 1 ?");
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("spring.data.redis.connect-timeout", () -> "200ms");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PharmacyFollowupMissedScheduler missedScheduler;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;

    @Test
    void requiresFeatureFlagBeforeAnyHandlerRuns() throws Exception {
        signup("fup-gate", "owner@fup-gate.test");
        String token = login("fup-gate", "owner@fup-gate.test");

        // Without the followup_workflow flag granted → 403 with the
        // FE's expected structured envelope.
        mockMvc.perform(get("/api/v1/pharmacy/followups")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FEATURE_NOT_ENABLED"))
                .andExpect(jsonPath("$.error.details[0].field").value("featureKey"))
                .andExpect(jsonPath("$.error.details[0].message").value("followup_workflow"));
    }

    @Test
    void createCompleteAndListWithFlagGranted() throws Exception {
        String tenantId = signup("fup-flow", "owner@fup-flow.test");
        String token = login("fup-flow", "owner@fup-flow.test");
        grantFeature(tenantId, "followup_workflow");
        UUID customerId = UUID.randomUUID();

        // Create — schedule a check for next week.
        OffsetDateTime due = OffsetDateTime.now().plusDays(7);
        MvcResult created = mockMvc.perform(post("/api/v1/pharmacy/followups")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId.toString(),
                                "dueDate", due.toString(),
                                "checkNote", "Check infection cleared"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.checkNote").value("Check infection cleared"))
                .andReturn();
        String fupId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // List — pagination envelope + the row appears.
        mockMvc.perform(get("/api/v1/pharmacy/followups")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(fupId))
                .andExpect(jsonPath("$.meta.total").value(1));

        // due-today is empty (we scheduled for +7 days).
        mockMvc.perform(get("/api/v1/pharmacy/followups/due-today")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // Complete — outcome recorded, status flips.
        mockMvc.perform(patch("/api/v1/pharmacy/followups/" + fupId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "outcome", "Patient recovered well.",
                                "outcomeType", "RECOVERED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.outcome").value("Patient recovered well."))
                .andExpect(jsonPath("$.data.outcomeType").value("RECOVERED"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());

        // Re-completing → 409 (status is no longer PENDING/MISSED).
        mockMvc.perform(patch("/api/v1/pharmacy/followups/" + fupId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "outcome", "Already done.",
                                "outcomeType", "RECOVERED"))))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelPendingAndRejectsCancelOnCompleted() throws Exception {
        String tenantId = signup("fup-cancel", "owner@fup-cancel.test");
        String token = login("fup-cancel", "owner@fup-cancel.test");
        grantFeature(tenantId, "followup_workflow");
        UUID customerId = UUID.randomUUID();

        MvcResult created = mockMvc.perform(post("/api/v1/pharmacy/followups")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId.toString(),
                                "dueDate", OffsetDateTime.now().plusHours(2).toString(),
                                "checkNote", "Test"))))
                .andExpect(status().isCreated())
                .andReturn();
        String fupId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(patch("/api/v1/pharmacy/followups/" + fupId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // Re-cancelling → 409.
        mockMvc.perform(patch("/api/v1/pharmacy/followups/" + fupId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict());
    }

    @Test
    void dueTodaySurfacesPendingInsideThe24HourWindow() throws Exception {
        String tenantId = signup("fup-due", "owner@fup-due.test");
        String token = login("fup-due", "owner@fup-due.test");
        grantFeature(tenantId, "followup_workflow");
        UUID customerId = UUID.randomUUID();

        // Create + backdate the row so due_date is "in 1 hour" — well
        // inside the 24h window.
        mockMvc.perform(post("/api/v1/pharmacy/followups")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId.toString(),
                                "dueDate", OffsetDateTime.now().plusHours(1).toString(),
                                "checkNote", "Call in 1h"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/pharmacy/followups/due-today")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void missedSweepFlipsPendingPastCutoff() throws Exception {
        String tenantId = signup("fup-miss", "owner@fup-miss.test");
        String token = login("fup-miss", "owner@fup-miss.test");
        grantFeature(tenantId, "followup_workflow");
        UUID customerId = UUID.randomUUID();

        MvcResult created = mockMvc.perform(post("/api/v1/pharmacy/followups")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId.toString(),
                                "dueDate", OffsetDateTime.now().plusHours(2).toString(),
                                "checkNote", "Will be missed"))))
                .andExpect(status().isCreated())
                .andReturn();
        String fupId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Backdate due_date to 3 days ago so it crosses the 48h cutoff.
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE pharmacy_followups SET due_date = ? WHERE id = ?::uuid")) {
            ps.setObject(1, OffsetDateTime.now(ZoneOffset.UTC).minusDays(3));
            ps.setString(2, fupId);
            ps.executeUpdate();
        }

        missedScheduler.runOnce();

        // Status now MISSED — the FE can show it on the missed list
        // and the pharmacist can still record an outcome.
        assertEquals("MISSED", readFollowupStatus(fupId));

        // Completing a MISSED row should still work (pharmacist ran late).
        mockMvc.perform(patch("/api/v1/pharmacy/followups/" + fupId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "outcome", "Reached patient on day 3.",
                                "outcomeType", "OTHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    // ----- helpers ----------------------------------------------------------

    private String signup(String slug, String adminEmail) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Business", "slug", slug,
                                "adminEmail", adminEmail, "adminPassword", PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText();
    }

    private String login(String slug, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", slug, "email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    /** Bypasses the admin grant endpoint — direct write since tests are read by an owner. */
    private void grantFeature(String tenantId, String featureKey) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO tenant_feature_flags (tenant_id, feature_key, status, enabled, granted_at) "
                             + "VALUES (?::uuid, ?, 'beta', true, now())")) {
            ps.setString(1, tenantId);
            ps.setString(2, featureKey);
            ps.executeUpdate();
        }
    }

    private String readFollowupStatus(String id) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT status FROM pharmacy_followups WHERE id = ?::uuid")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
