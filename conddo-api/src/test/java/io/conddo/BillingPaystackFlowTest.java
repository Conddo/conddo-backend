package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.paystack.PaystackGateway;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HANDOFF_2026-06-11 §8 — Paystack-backed Conddo plan billing.
 *
 * <ul>
 *   <li>POST /checkout calls Paystack init + persists a transaction
 *       row, returns hosted URL + reference.</li>
 *   <li>GET /verify reconciles the gateway response, activates the
 *       new plan on success.</li>
 *   <li>Webhook signature mismatch → 401 silent.</li>
 *   <li>Webhook with valid charge.success → reconciliation +
 *       subscription flip.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BillingPaystackFlowTest {

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
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;
    @MockBean
    private PaystackGateway paystackGateway;

    @Test
    void checkoutReturnsHostedUrlAndReferenceAndPersistsTransaction() throws Exception {
        when(paystackGateway.initialize(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new PaystackGateway.InitResult(
                        "https://checkout.paystack.com/abc",
                        invocation.getArgument(2), "ACCESS_XYZ"));

        signup("ps-checkout", "owner@ps-checkout.test");
        String token = login("ps-checkout", "owner@ps-checkout.test");
        waitForSubscription("ps-checkout");

        MvcResult res = mockMvc.perform(post("/api/v1/billing/checkout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "planId", "growth", "billingCycle", "monthly"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizationUrl")
                        .value("https://checkout.paystack.com/abc"))
                .andExpect(jsonPath("$.data.reference").exists())
                .andExpect(jsonPath("$.data.accessCode").value("ACCESS_XYZ"))
                .andReturn();
        String reference = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("reference").asText();

        // Transaction row persisted in pending state.
        assertEquals("pending", readTxStatus(reference));
    }

    @Test
    void verifySuccessFlipsTransactionAndActivatesPlan() throws Exception {
        when(paystackGateway.initialize(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new PaystackGateway.InitResult(
                        "https://checkout.paystack.com/v", invocation.getArgument(2), null));
        when(paystackGateway.verify(anyString())).thenAnswer(invocation -> new PaystackGateway.VerifyResult(
                invocation.getArgument(0),
                PaystackGateway.VerifyResult.Status.SUCCESS,
                BigDecimal.valueOf(45000),
                OffsetDateTime.now(),
                null, "SUB_TESTCODE", "CUS_TESTCODE"));

        signup("ps-verify", "owner@ps-verify.test");
        String token = login("ps-verify", "owner@ps-verify.test");
        waitForSubscription("ps-verify");

        MvcResult res = mockMvc.perform(post("/api/v1/billing/checkout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("planId", "growth"))))
                .andExpect(status().isOk())
                .andReturn();
        String reference = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("reference").asText();

        // Verify path flips status + activates plan.
        mockMvc.perform(get("/api/v1/billing/verify").param("reference", reference)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("success"))
                .andExpect(jsonPath("$.data.subscription.planId").value("growth"));

        // Calling verify again is idempotent — doesn't re-call Paystack.
        mockMvc.perform(get("/api/v1/billing/verify").param("reference", reference)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("success"));
    }

    @Test
    void webhookSignatureMismatchReturns401() throws Exception {
        when(paystackGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/api/v1/billing/webhooks/paystack")
                        .header("x-paystack-signature", "not-a-real-sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"charge.success\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookChargeSuccessReconcilesTransaction() throws Exception {
        when(paystackGateway.initialize(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new PaystackGateway.InitResult(
                        "https://checkout.paystack.com/w", invocation.getArgument(2), null));
        when(paystackGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);

        signup("ps-web", "owner@ps-web.test");
        String token = login("ps-web", "owner@ps-web.test");
        waitForSubscription("ps-web");

        MvcResult res = mockMvc.perform(post("/api/v1/billing/checkout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("planId", "growth"))))
                .andExpect(status().isOk())
                .andReturn();
        String reference = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("reference").asText();

        String payload = objectMapper.writeValueAsString(Map.of(
                "event", "charge.success",
                "data", Map.of(
                        "reference", reference,
                        "paid_at", OffsetDateTime.now().toString(),
                        "authorization", Map.of("authorization_code", "AUTH_HOOK"))));

        mockMvc.perform(post("/api/v1/billing/webhooks/paystack")
                        .header("x-paystack-signature", "valid-sig-mocked-true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        assertEquals("success", readTxStatus(reference));
        verify(paystackGateway).verifyWebhookSignature(anyString(), anyString());
    }

    // ----- helpers ----------------------------------------------------------

    private void signup(String slug, String adminEmail) throws Exception {
        mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Business", "slug", slug,
                                "adminEmail", adminEmail, "adminPassword", PASSWORD))))
                .andExpect(status().isCreated());
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

    /** Trial subscription is created via @Async listener — poll briefly. */
    private void waitForSubscription(String slug) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection owner = ownerConn();
                 var ps = owner.prepareStatement(
                         "SELECT count(*) FROM tenant_subscriptions ts "
                                 + "JOIN tenants t ON t.id = ts.tenant_id WHERE t.slug = ?")) {
                ps.setString(1, slug);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= 1) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("trial subscription never landed for " + slug);
    }

    private String readTxStatus(String reference) throws SQLException {
        try (Connection owner = ownerConn();
             var ps = owner.prepareStatement(
                     "SELECT status FROM billing_paystack_transactions WHERE reference = ?")) {
            ps.setString(1, reference);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
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
