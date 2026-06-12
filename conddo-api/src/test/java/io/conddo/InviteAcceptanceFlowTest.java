package io.conddo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end invite acceptance (HANDOFF_2026-06-12 §5): owner
 * invites a CASHIER, BE emails the accept URL, invitee previews +
 * accepts, the resulting access token authorises POS work.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InviteAcceptanceFlowTest {

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

    @Test
    void inviteCashierThenPreviewAndAcceptYieldsWorkingLogin() throws Exception {
        String tenantId = signup("inv-happy", "owner@inv-happy.test");
        String ownerToken = login("inv-happy", "owner@inv-happy.test");
        grantFeature(tenantId, "pos");

        // Owner invites a CASHIER. Email goes out with the accept URL.
        MvcResult invited = mockMvc.perform(post("/api/v1/staff/invite")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "tunde@inv-happy.test",
                                "staffRole", "CASHIER",
                                "fullName", "Tunde Bello"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.staffRole").value("CASHIER"))
                .andExpect(jsonPath("$.data.status").value("invited"))
                .andReturn();

        String token = captureTokenFromEmail("tunde@inv-happy.test");
        assertNotNull(token, "Invite email must include the ?token=... URL");

        // Preview returns the friendly shape.
        mockMvc.perform(get("/auth/invite/preview").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantName").value("inv-happy Biz"))
                .andExpect(jsonPath("$.data.roleLabel").value("Cashier"))
                .andExpect(jsonPath("$.data.staffRole").value("CASHIER"))
                .andExpect(jsonPath("$.data.email").value("tunde@inv-happy.test"));

        // Accept the invite — sets password, flips to ACTIVE, returns a login response.
        MvcResult accepted = mockMvc.perform(post("/auth/accept-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", token,
                                "password", PASSWORD,
                                "fullName", "Tunde Bello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.role").value("STAFF"))
                .andReturn();

        // The acceptance token authorises CASHIER-gated work (POS).
        String cashierToken = objectMapper.readTree(accepted.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(cashierToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("openingFloat", 0))))
                .andExpect(status().isCreated());

        // Second-use of the same token now fails.
        mockMvc.perform(post("/auth/accept-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", token, "password", PASSWORD))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITE_INVALID"));
    }

    @Test
    void previewWithBogusTokenReturns404() throws Exception {
        mockMvc.perform(get("/auth/invite/preview").param("token", "not-a-real-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITE_INVALID"));
    }

    @Test
    void updatingTheOwnerRowReturnsOwnerProtected() throws Exception {
        String tenantId = signup("inv-owner", "owner@inv-owner.test");
        String token = login("inv-owner", "owner@inv-owner.test");
        String ownerId = readOwnerId(tenantId);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/staff/" + ownerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("OWNER_PROTECTED"));
    }

    @Test
    void shortPasswordRejected() throws Exception {
        String tenantId = signup("inv-short", "owner@inv-short.test");
        String ownerToken = login("inv-short", "owner@inv-short.test");
        mockMvc.perform(post("/api/v1/staff/invite")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "shorty@inv-short.test", "staffRole", "MANAGER"))))
                .andExpect(status().isCreated());
        String token = captureTokenFromEmail("shorty@inv-short.test");

        mockMvc.perform(post("/auth/accept-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", token, "password", "abc"))))
                .andExpect(status().isBadRequest());
    }

    // ----- helpers ----------------------------------------------------------

    private String signup(String slug, String adminEmail) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Biz", "slug", slug,
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

    private String readOwnerId(String tenantId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT id FROM users WHERE tenant_id = ?::uuid AND role = 'TENANT_ADMIN' LIMIT 1")) {
            ps.setString(1, tenantId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    /** Pull the raw token out of the invite email body. */
    private String captureTokenFromEmail(String invitedEmail) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(invitedEmail), anyString(), body.capture());
        Matcher m = Pattern.compile("\\?token=([A-Za-z0-9_-]+)").matcher(body.getValue());
        return m.find() ? m.group(1) : null;
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
