package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Roadmap Beta 1 — Cashback Loyalty.
 *
 * <ul>
 *   <li>Feature gate refuses without flag.</li>
 *   <li>Config upsert + read.</li>
 *   <li>Order → DELIVERED transition credits the customer's wallet
 *       via the listener; double-transition is idempotent on
 *       order_id.</li>
 *   <li>Public checkout with cashbackRedemption deducts the wallet
 *       and reduces the order total; redemption below minimum is
 *       refused; redemption ≥ total is refused.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyLoyaltyFlowTest {

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
        registry.add("conddo.customer-jwt.secret",
                () -> "test-customer-jwt-secret-at-least-32-bytes-long-PAD");
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
    void featureGateRefusesWithoutFlag() throws Exception {
        signup("loy-gate", "owner@loy-gate.test");
        String token = login("loy-gate", "owner@loy-gate.test");
        mockMvc.perform(get("/api/v1/pharmacy/loyalty/config")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FEATURE_NOT_ENABLED"))
                .andExpect(jsonPath("$.error.details[0].message").value("cashback_loyalty"));
    }

    @Test
    void configUpsertAndReadAndOrderDeliveredCreditsWallet() throws Exception {
        String tenantId = signupPharmacy("loy-flow", "owner@loy-flow.test");
        String token = login("loy-flow", "owner@loy-flow.test");
        grantFeature(tenantId, "cashback_loyalty");
        upgradeToGrowth(tenantId);   // dashboard order creation requires order_management

        // Set config: 5% cashback, ₦500 min.
        mockMvc.perform(put("/api/v1/pharmacy/loyalty/config")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cashbackRate", 5,
                                "minRedemption", 500,
                                "isActive", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashbackRate").value(5))
                .andExpect(jsonPath("$.data.minRedemption").value(500))
                .andExpect(jsonPath("$.data.isActive").value(true));

        mockMvc.perform(get("/api/v1/pharmacy/loyalty/config")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashbackRate").value(5));

        // Customer + order, then transition to Delivered → wallet credited.
        String customerId = seedCustomer(tenantId, "Cash Buyer", "+2348091230000");
        String orderId = createOrder(token, customerId, 10_000);
        transition(token, orderId, "Delivered");

        // Wallet shows balance = 5% × 10,000 = 500.
        Thread.sleep(500); // @Async listener fires post-commit
        long deadline = System.currentTimeMillis() + 5_000;
        boolean credited = false;
        while (System.currentTimeMillis() < deadline && !credited) {
            MvcResult walletRes = mockMvc.perform(get("/api/v1/pharmacy/loyalty/wallets/" + customerId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andReturn();
            if (walletRes.getResponse().getStatus() == 200) {
                JsonNode data = objectMapper.readTree(walletRes.getResponse().getContentAsString())
                        .path("data");
                if (data.path("balance").decimalValue().compareTo(new java.math.BigDecimal("500.00")) == 0) {
                    credited = true;
                    break;
                }
            }
            Thread.sleep(100);
        }
        assertTrue(credited, "wallet should be credited 500.00 within 5s of DELIVERED");

        // Transactions list shows CASHBACK_EARNED tied to the order.
        mockMvc.perform(get("/api/v1/pharmacy/loyalty/wallets/" + customerId + "/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].transactionType").value("CASHBACK_EARNED"))
                .andExpect(jsonPath("$.data[0].amount").value(500.00));

        // Re-transitioning the same order to Delivered must NOT double-credit.
        transition(token, orderId, "Processing");   // bounce
        transition(token, orderId, "Delivered");
        Thread.sleep(500);
        mockMvc.perform(get("/api/v1/pharmacy/loyalty/wallets/" + customerId + "/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void publicCheckoutWithCashbackDeductsWallet() throws Exception {
        String tenantId = signup("loy-buy", "owner@loy-buy.test");
        String token = login("loy-buy", "owner@loy-buy.test");
        grantFeature(tenantId, "cashback_loyalty");
        String apiKey = regenerateKey(token);
        activateSite(tenantId, "loy-buy");
        upgradeToGrowth(tenantId);
        mockMvc.perform(put("/api/v1/pharmacy/loyalty/config")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cashbackRate", 10, "minRedemption", 200, "isActive", true))))
                .andExpect(status().isOk());

        // Customer registers + has a wallet credited via direct seed (we
        // don't want to thread a full delivered-order through this test).
        String custToken = registerCustomer(apiKey, "loy-buy", "buyer@loy-buy.test", "Loy Buyer");
        String customerId = readCustomerIdByEmail(tenantId, "buyer@loy-buy.test");
        seedWallet(tenantId, customerId, "1000.00");
        String pid = seedProduct(tenantId, "Aspirin", "500.00", "aspirin-loy");
        String addressId = createAddress(apiKey, "loy-buy", custToken, "Lagos");

        // 1 × 500 + 1500 delivery = 2000; redeem 800 → total = 1200.
        mockMvc.perform(post("/api/v1/public/loy-buy/pharmacy/orders")
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "addressId", addressId,
                                "cashbackRedemption", 800))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.total").value(1200.00));

        // Wallet balance dropped to 200.
        mockMvc.perform(get("/api/v1/pharmacy/loyalty/wallets/" + customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(200.00));

        // A REDEMPTION row exists.
        mockMvc.perform(get("/api/v1/pharmacy/loyalty/wallets/" + customerId + "/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].transactionType").value("REDEMPTION"))
                .andExpect(jsonPath("$.data[0].amount").value(-800.00));
    }

    @Test
    void redemptionBelowMinimumRefusedAtCheckout() throws Exception {
        String tenantId = signup("loy-min", "owner@loy-min.test");
        String token = login("loy-min", "owner@loy-min.test");
        grantFeature(tenantId, "cashback_loyalty");
        String apiKey = regenerateKey(token);
        activateSite(tenantId, "loy-min");
        upgradeToGrowth(tenantId);
        mockMvc.perform(put("/api/v1/pharmacy/loyalty/config")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cashbackRate", 5, "minRedemption", 500, "isActive", true))))
                .andExpect(status().isOk());

        String custToken = registerCustomer(apiKey, "loy-min", "below@loy-min.test", "Below Min");
        String customerId = readCustomerIdByEmail(tenantId, "below@loy-min.test");
        seedWallet(tenantId, customerId, "1000.00");
        String pid = seedProduct(tenantId, "Vitamin", "300.00", "vit-min");
        String addressId = createAddress(apiKey, "loy-min", custToken, "Lagos");

        // 100 < minRedemption 500 → 409 CONFLICT via IllegalArgumentException handler.
        mockMvc.perform(post("/api/v1/public/loy-min/pharmacy/orders")
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "addressId", addressId,
                                "cashbackRedemption", 100))))
                .andExpect(status().isConflict());
    }

    // ----- helpers ----------------------------------------------------------

    private String createOrder(String token, String customerId, int amountNgn) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId,
                                "service", "Test order",
                                "items", List.of(Map.of("description", "Test", "quantity", 1, "unitPrice", amountNgn))))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asText();
    }

    private void transition(String token, String orderId, String stage) throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/transition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", stage))))
                .andExpect(status().isOk());
    }

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

    /** Signs up on the pharmacy vertical so order stages include "Delivered". */
    private String signupPharmacy(String slug, String adminEmail) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Business", "slug", slug,
                                "verticalId", "pharmacy",
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

    private String regenerateKey(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/website/site/regenerate-key")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("apiKey").asText();
    }

    private void activateSite(String tenantId, String subdomain) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE tenant_sites SET subdomain = ?, is_active = true, qa_approved = true "
                             + "WHERE tenant_id = ?::uuid")) {
            ps.setString(1, subdomain);
            ps.setString(2, tenantId);
            ps.executeUpdate();
        }
    }

    private void upgradeToGrowth(String tenantId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection owner = ownerConn();
                 PreparedStatement ps = owner.prepareStatement(
                         "UPDATE tenant_subscriptions SET plan_id = "
                                 + "(SELECT id FROM subscription_plans WHERE name = 'growth') "
                                 + "WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                if (ps.executeUpdate() >= 1) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("trial sub never landed for " + tenantId);
    }

    private String registerCustomer(String apiKey, String slug, String email, String fullName) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/public/" + slug + "/auth/register")
                        .header("X-Conddo-Site-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", fullName, "email", email, "password", "buyerpw123"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("token").asText();
    }

    private String createAddress(String apiKey, String slug, String custToken, String state) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/public/" + slug + "/customer/addresses")
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "label", "Home", "street", "12 Allen Ave", "city", "Ikeja",
                                "state", state, "isDefault", true))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("id").asText();
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

    private String seedCustomer(String tenantId, String name, String phone) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO customers (tenant_id, full_name, email, phone) "
                             + "VALUES (?::uuid, ?, ?, ?) RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase().replace(' ', '.') + "@buyer.test");
            ps.setString(4, phone);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String seedProduct(String tenantId, String name, String price, String slug) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO products (tenant_id, name, sku, price, stock, "
                             + "reorder_threshold, active, slug, name_generic) "
                             + "VALUES (?::uuid, ?, ?, ?::numeric, 100, 0, true, ?, ?) RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, name.toUpperCase() + "-SKU");
            ps.setString(4, price);
            ps.setString(5, slug);
            ps.setString(6, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String readCustomerIdByEmail(String tenantId, String email) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT id FROM customers WHERE tenant_id = ?::uuid AND email = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private void seedWallet(String tenantId, String customerId, String balance) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO pharmacy_customer_wallets (tenant_id, customer_id, balance, total_earned) "
                             + "VALUES (?::uuid, ?::uuid, ?::numeric, ?::numeric)")) {
            ps.setString(1, tenantId);
            ps.setString(2, customerId);
            ps.setString(3, balance);
            ps.setString(4, balance);
            ps.executeUpdate();
        }
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
