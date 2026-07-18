package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Roadmap — tenant feature flag groundwork.
 *
 * <ul>
 *   <li>{@code GET /feature-flags} renders every catalogue key with
 *       status + interest + enabled, even for a fresh signup with no
 *       interaction yet.</li>
 *   <li>{@code POST /feature-interest} stamps interest=true on a
 *       Coming Soon item.</li>
 *   <li>{@code POST /beta-access-request} stamps interest=true on a
 *       Beta item and rejects 400 on a non-beta key.</li>
 *   <li>SUPER_ADMIN grant flips enabled=true even when the tenant
 *       has no prior row; SUPER_ADMIN revoke walks it back.</li>
 *   <li>Tenant-side requests for an unknown feature key → 409
 *       CONFLICT (IllegalArgumentException via the existing handler).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TenantFeatureFlagFlowTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";
    private static final String PASSWORD = "password123";

    private static final String SUPER_EMAIL = "super-ff@conddo.io";
    private static final String SUPER_PASSWORD = "super-ff-pw";
    private static final String SUPER_PASSWORD_HASH = new PasswordHasher().hash(SUPER_PASSWORD);

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
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("spring.data.redis.connect-timeout", () -> "200ms");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;

    @BeforeEach
    void seedSuperAdmin() throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO staff_users (email, password_hash, full_name, internal_role) "
                             + "VALUES (?, ?, ?, 'SUPER_ADMIN') ON CONFLICT (email) DO NOTHING")) {
            ps.setString(1, SUPER_EMAIL);
            ps.setString(2, SUPER_PASSWORD_HASH);
            ps.setString(3, "Feature Flag Admin");
            ps.executeUpdate();
        }
    }

    @Test
    void freshTenantSeesEntireCatalogueWithDefaultStatuses() throws Exception {
        signup("ff-fresh", "owner@ff-fresh.test");
        String token = login("ff-fresh", "owner@ff-fresh.test");

        MvcResult res = mockMvc.perform(get("/api/v1/feature-flags")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
        assertTrue(rows.isArray(), "expected array");
        // Catalogue is currently 10 features (5 beta + 5 coming soon) — POS
        // graduated from coming_soon to beta when the pharmacy POS shipped.
        assertEquals(10, rows.size());
        // None should be enabled or carry interest for a brand-new tenant.
        for (JsonNode row : rows) {
            assertEquals(false, row.path("enabled").asBoolean(), "row not enabled: " + row);
            assertEquals(false, row.path("interest").asBoolean(), "row no interest: " + row);
        }
        // At least one Beta key is present with status="beta".
        boolean cashback = false;
        for (JsonNode row : rows) {
            if ("cashback_loyalty".equals(row.path("featureKey").asText())) {
                assertEquals("beta", row.path("status").asText());
                cashback = true;
            }
        }
        assertTrue(cashback, "cashback_loyalty must be in catalogue");
    }

    @Test
    void registerInterestOnComingSoonFlipsBitAndShowsInList() throws Exception {
        signup("ff-int", "owner@ff-int.test");
        String token = login("ff-int", "owner@ff-int.test");

        mockMvc.perform(post("/api/v1/feature-interest")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "featureKey", "multi_store"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.message").value(
                        org.hamcrest.Matchers.containsString("on the list")))
                .andExpect(jsonPath("$.data.flag.featureKey").value("multi_store"))
                .andExpect(jsonPath("$.data.flag.interest").value(true))
                .andExpect(jsonPath("$.data.flag.enabled").value(false));

        // The same row surfaces in the list with interest=true.
        MvcResult listRes = mockMvc.perform(get("/api/v1/feature-flags")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(listRes.getResponse().getContentAsString()).path("data");
        boolean found = false;
        for (JsonNode row : rows) {
            if ("multi_store".equals(row.path("featureKey").asText())) {
                assertEquals(true, row.path("interest").asBoolean());
                assertEquals(false, row.path("enabled").asBoolean());
                found = true;
            }
        }
        assertTrue(found, "interest row must surface in catalogue list");
    }

    @Test
    void requestBetaAccessOnNonBetaFeatureReturns409() throws Exception {
        signup("ff-bad", "owner@ff-bad.test");
        String token = login("ff-bad", "owner@ff-bad.test");

        // multi_store is Coming Soon, not Beta — should be rejected.
        mockMvc.perform(post("/api/v1/beta-access-request")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "featureKey", "multi_store"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void unknownFeatureKeyReturns409() throws Exception {
        signup("ff-junk", "owner@ff-junk.test");
        String token = login("ff-junk", "owner@ff-junk.test");

        mockMvc.perform(post("/api/v1/feature-interest")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "featureKey", "rocket_launcher"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void superAdminGrantAndRevokeFlipsEnabledFlag() throws Exception {
        String tenantId = signup("ff-grant", "owner@ff-grant.test");
        String token = login("ff-grant", "owner@ff-grant.test");
        String staffToken = staffLogin();

        // Tenant requests beta access (the natural flow).
        mockMvc.perform(post("/api/v1/beta-access-request")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "featureKey", "cashback_loyalty"))))
                .andExpect(status().isCreated());

        // SUPER_ADMIN grants — enabled flips to true with grant stamps.
        mockMvc.perform(post("/api/v1/admin/tenants/" + tenantId
                                + "/feature-flags/cashback_loyalty/grant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(staffToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flag.enabled").value(true))
                .andExpect(jsonPath("$.data.flag.grantedAt").isNotEmpty());

        // Tenant's own list reflects the grant.
        MvcResult listRes = mockMvc.perform(get("/api/v1/feature-flags")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(listRes.getResponse().getContentAsString()).path("data");
        for (JsonNode row : rows) {
            if ("cashback_loyalty".equals(row.path("featureKey").asText())) {
                assertEquals(true, row.path("enabled").asBoolean());
            }
        }

        // Revoke — enabled flips back.
        mockMvc.perform(delete("/api/v1/admin/tenants/" + tenantId
                                + "/feature-flags/cashback_loyalty/grant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(staffToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flag.enabled").value(false))
                .andExpect(jsonPath("$.data.flag.grantedAt").doesNotExist());
    }

    @Test
    void tenantAdminCantCallAdminGrantEndpoint() throws Exception {
        String tenantId = signup("ff-rbac", "owner@ff-rbac.test");
        String tenantToken = login("ff-rbac", "owner@ff-rbac.test");

        mockMvc.perform(post("/api/v1/admin/tenants/" + tenantId
                                + "/feature-flags/cashback_loyalty/grant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenantToken)))
                .andExpect(status().isForbidden());
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

    private String staffLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/staff/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", SUPER_EMAIL, "password", SUPER_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
