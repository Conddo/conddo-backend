package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.social.AyrshareClient;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the SOCIAL_AND_CREATIVE_SERVICES_SPEC Phase 1
 * surface (connect via Ayrshare + compose/schedule/publish + webhook
 * reconcile + plan gating). Boots the real Spring context against
 * Testcontainers Postgres (Flyway V1–V27); the Ayrshare HTTP path is
 * stubbed with a {@code @MockBean} so the tests don't depend on the
 * upstream being reachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SocialMarketingFlowTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";
    private static final String PASSWORD = "password123";

    private static final String WEBHOOK_SECRET = "test-ayrshare-webhook-secret";
    /** AES-256 envelope key for SocialTokenCipher — base64 of 32 deterministic bytes. */
    private static final String TOKEN_KEY_BASE64 = Base64.getEncoder()
            .encodeToString(new byte[]{
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                    17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});

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
        registry.add("conddo.social.token-key", () -> TOKEN_KEY_BASE64);
        registry.add("conddo.social.ayrshare-webhook-secret", () -> WEBHOOK_SECRET);
        // Pin expiry cron far-future so it doesn't fire during the test run.
        registry.add("conddo.billing.expiry-cron", () -> "0 0 0 1 1 ?");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;
    @MockBean
    private AyrshareClient ayrshareClient;

    // ----- connect lifecycle -------------------------------------------------

    @Test
    void connectLinkCreatesAyrshareProfileOnceAndReturnsHostedUrl() throws Exception {
        when(ayrshareClient.isConfigured()).thenReturn(true);
        when(ayrshareClient.createProfile(anyString())).thenReturn(
                Optional.of(new AyrshareClient.ProfileCreate("ay-key-abc", "ph-connect Business")));
        when(ayrshareClient.connectLink("ay-key-abc")).thenReturn(
                Optional.of("https://app.ayrshare.com/hosted/connect/xyz"));

        String tenantId = signup("ph-connect", "owner@ph-connect.test");
        String token = login("ph-connect", "owner@ph-connect.test");
        upgradeToGrowth(tenantId);

        mockMvc.perform(post("/api/v1/marketing/social/connect-link")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectUrl").value(
                        "https://app.ayrshare.com/hosted/connect/xyz"));

        // Second call — profile already exists, no new createProfile, just a fresh URL.
        when(ayrshareClient.connectLink("ay-key-abc")).thenReturn(
                Optional.of("https://app.ayrshare.com/hosted/connect/zyx"));
        mockMvc.perform(post("/api/v1/marketing/social/connect-link")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        verify(ayrshareClient, times(1)).createProfile(anyString());

        // The stored key is encrypted — base64'd AES-GCM, NOT the plaintext.
        String storedKey = readProfileKey(tenantId);
        assertNotNull(storedKey);
        assertTrue(!storedKey.contains("ay-key-abc"),
                "stored profileKey must not be plaintext: " + storedKey);
    }

    @Test
    void launcherTenantHitsPlanGateOnSocialEndpoints() throws Exception {
        when(ayrshareClient.isConfigured()).thenReturn(true);
        signup("ph-gate", "owner@ph-gate.test");
        String token = login("ph-gate", "owner@ph-gate.test");
        // Stay on launcher — social_scheduler is OFF on launcher per V24 seed.

        // RequiresFeatureInterceptor returns {"error": "PLAN_UPGRADE_REQUIRED", ...}
        // — error is a string, not the GlobalExceptionHandler envelope.
        mockMvc.perform(post("/api/v1/marketing/social/connect-link")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PLAN_UPGRADE_REQUIRED"));
    }

    @Test
    void unconfiguredAyrshareReturnsCleanServiceUnavailable() throws Exception {
        when(ayrshareClient.isConfigured()).thenReturn(false);
        String tenantId = signup("ph-unconf", "owner@ph-unconf.test");
        String token = login("ph-unconf", "owner@ph-unconf.test");
        upgradeToGrowth(tenantId);

        mockMvc.perform(post("/api/v1/marketing/social/connect-link")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SOCIAL_UNCONFIGURED"));
    }

    // ----- posts -------------------------------------------------------------

    @Test
    void scheduledPostStoresAyrsharePostIdAndPendingTargets() throws Exception {
        when(ayrshareClient.isConfigured()).thenReturn(true);
        when(ayrshareClient.createProfile(anyString())).thenReturn(
                Optional.of(new AyrshareClient.ProfileCreate("ay-key-sched", "ph-sched Business")));
        when(ayrshareClient.connectLink(anyString())).thenReturn(
                Optional.of("https://app.ayrshare.com/hosted/x"));
        Map<String, AyrshareClient.TargetResult> targets = Map.of(
                "facebook", new AyrshareClient.TargetResult("pending", null, null),
                "instagram", new AyrshareClient.TargetResult("pending", null, null));
        when(ayrshareClient.publish(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(new AyrshareClient.PostDispatch("ayrshare-sched-123", targets)));

        String tenantId = signup("ph-sched", "owner@ph-sched.test");
        String token = login("ph-sched", "owner@ph-sched.test");
        upgradeToGrowth(tenantId);
        mockMvc.perform(post("/api/v1/marketing/social/connect-link")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        OffsetDateTime tomorrow = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);
        MvcResult result = mockMvc.perform(post("/api/v1/marketing/social/posts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "caption", "Launch day! 🚀",
                                "scheduledAt", tomorrow.toString(),
                                "platforms", List.of("facebook", "instagram")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("scheduled"))
                .andExpect(jsonPath("$.data.ayrsharePostId").value("ayrshare-sched-123"))
                .andExpect(jsonPath("$.data.targets.length()").value(2))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data");
        // Both targets are pending — Ayrshare will reconcile via webhook.
        for (JsonNode t : body.path("targets")) {
            assertEquals("pending", t.path("status").asText());
        }
    }

    @Test
    void immediatePublishMarksTargetsPublishedWhenAyrshareReportsSuccess() throws Exception {
        when(ayrshareClient.isConfigured()).thenReturn(true);
        when(ayrshareClient.createProfile(anyString())).thenReturn(
                Optional.of(new AyrshareClient.ProfileCreate("ay-key-now", "ph-now Business")));
        when(ayrshareClient.connectLink(anyString())).thenReturn(Optional.of("https://x"));
        Map<String, AyrshareClient.TargetResult> targets = Map.of(
                "facebook", new AyrshareClient.TargetResult(
                        "published", "https://facebook.com/post/1", null));
        when(ayrshareClient.publish(anyString(), anyString(), any(), any(), eq(null)))
                .thenReturn(Optional.of(new AyrshareClient.PostDispatch(null, targets)));

        String tenantId = signup("ph-now", "owner@ph-now.test");
        String token = login("ph-now", "owner@ph-now.test");
        upgradeToGrowth(tenantId);
        mockMvc.perform(post("/api/v1/marketing/social/connect-link")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        // scheduledAt within the next minute → publish immediately.
        OffsetDateTime soon = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(10).withNano(0);
        mockMvc.perform(post("/api/v1/marketing/social/posts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "caption", "Hot take.",
                                "scheduledAt", soon.toString(),
                                "platforms", List.of("facebook")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("published"))
                .andExpect(jsonPath("$.data.targets[0].status").value("published"))
                .andExpect(jsonPath("$.data.targets[0].externalPostId").value(
                        "https://facebook.com/post/1"));
    }

    // ----- webhook reconcile -------------------------------------------------

    @Test
    void webhookReconcileMarksTargetPublished() throws Exception {
        when(ayrshareClient.isConfigured()).thenReturn(true);
        when(ayrshareClient.createProfile(anyString())).thenReturn(
                Optional.of(new AyrshareClient.ProfileCreate("ay-key-wh", "ph-wh Business")));
        when(ayrshareClient.connectLink(anyString())).thenReturn(Optional.of("https://x"));
        Map<String, AyrshareClient.TargetResult> pending = Map.of(
                "facebook", new AyrshareClient.TargetResult("pending", null, null));
        when(ayrshareClient.publish(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(new AyrshareClient.PostDispatch("ayrshare-wh-9", pending)));

        String tenantId = signup("ph-wh", "owner@ph-wh.test");
        String token = login("ph-wh", "owner@ph-wh.test");
        upgradeToGrowth(tenantId);
        mockMvc.perform(post("/api/v1/marketing/social/connect-link")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        OffsetDateTime tomorrow = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);
        mockMvc.perform(post("/api/v1/marketing/social/posts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "caption", "Reconcile me",
                                "scheduledAt", tomorrow.toString(),
                                "platforms", List.of("facebook")))))
                .andExpect(status().isCreated());

        // Ayrshare fires the webhook AFTER it publishes the scheduled post.
        Map<String, Object> webhookPayload = new LinkedHashMap<>();
        webhookPayload.put("action", "post.published");
        webhookPayload.put("id", "ayrshare-wh-9");
        webhookPayload.put("platform", "facebook");
        webhookPayload.put("postUrl", "https://facebook.com/post/9");
        byte[] raw = objectMapper.writeValueAsBytes(webhookPayload);
        String hex = hmacHex(raw, WEBHOOK_SECRET);

        mockMvc.perform(post("/webhooks/ayrshare")
                        .header("X-Ayrshare-Signature", hex)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isOk());

        // Verify the target row moved to published.
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT status, external_post_id FROM social_post_targets "
                             + "WHERE tenant_id = ?::uuid AND provider = 'facebook'")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected one target row");
                assertEquals("published", rs.getString(1));
                assertEquals("https://facebook.com/post/9", rs.getString(2));
            }
        }
    }

    @Test
    void webhookWithBadSignatureIsRejected() throws Exception {
        when(ayrshareClient.isConfigured()).thenReturn(true);
        Map<String, Object> payload = Map.of("action", "post.published", "id", "anything");
        mockMvc.perform(post("/webhooks/ayrshare")
                        .header("X-Ayrshare-Signature", "deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isUnauthorized());
    }

    // ----- helpers -----------------------------------------------------------

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

    private void upgradeToGrowth(String tenantId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection owner = ownerConn();
                 PreparedStatement ps = owner.prepareStatement(
                         "UPDATE tenant_subscriptions "
                                 + "SET plan_id = (SELECT id FROM subscription_plans WHERE name = 'growth') "
                                 + "WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                if (ps.executeUpdate() >= 1) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("trial subscription never landed for " + tenantId);
    }

    private String readProfileKey(String tenantId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT ayrshare_profile_key FROM tenant_social_profile WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a tenant_social_profile row");
                return rs.getString(1);
            }
        }
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String hmacHex(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
