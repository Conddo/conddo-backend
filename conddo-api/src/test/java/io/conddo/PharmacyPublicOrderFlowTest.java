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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V33 — Slice 2 of PHARMACY_PUBLIC_API_SPEC. The customer order loop:
 * <ul>
 *   <li>§7 customer address CRUD</li>
 *   <li>§4 server-side cart (upsert REPLACES quantity, remove, clear)</li>
 *   <li>§6 customer-initiated prescription upload + history</li>
 *   <li>§5 checkout — pessimistic stock lock, prescription gate,
 *       delivery fee from saved address, cart cleared on success</li>
 *   <li>Merchant notification side-effect (port from Phase-1 test)</li>
 *   <li>Module gating — launcher plan → 403 MODULE_NOT_ENABLED</li>
 *   <li>Cross-customer order detail refused</li>
 * </ul>
 *
 * <p>Each test boots the full app + Postgres container, exercises a
 * unique tenant slug, and registers its own customer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyPublicOrderFlowTest {

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
        registry.add("conddo.customer-jwt.secret",
                () -> "test-customer-jwt-secret-at-least-32-bytes-long-PAD");
        registry.add("conddo.customer-jwt.ttl", () -> "1d");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;

    // ========================= §7 addresses ===================================

    @Test
    void addressCrudListsCreatesAndDeletes() throws Exception {
        Site site = bootSite("ph-addr");
        String custToken = registerCustomer(site, "alice@buyer.test", "Alice Buyer");

        // Empty list at start.
        mockMvc.perform(get("/api/v1/public/ph-addr/customer/addresses")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses.length()").value(0));

        // Create one — should come back with the Lagos delivery quote attached.
        MvcResult created = mockMvc.perform(post("/api/v1/public/ph-addr/customer/addresses")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "label", "Home",
                                "street", "12 Allen Ave",
                                "city", "Ikeja",
                                "state", "Lagos",
                                "isDefault", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.deliveryFee").value(1500))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andReturn();
        String addressId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("id").asText();

        // List shows it.
        mockMvc.perform(get("/api/v1/public/ph-addr/customer/addresses")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses.length()").value(1))
                .andExpect(jsonPath("$.addresses[0].id").value(addressId));

        // Anonymous request → 401 (no customer JWT).
        mockMvc.perform(get("/api/v1/public/ph-addr/customer/addresses")
                        .header("X-Conddo-Site-Key", site.apiKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        // Delete works.
        mockMvc.perform(delete("/api/v1/public/ph-addr/customer/addresses/" + addressId)
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/public/ph-addr/customer/addresses")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses.length()").value(0));
    }

    // ========================= §4 cart =======================================

    @Test
    void cartUpsertReplacesQuantityAndRemoveAndClearWork() throws Exception {
        Site site = bootSite("ph-cart");
        upgradeToGrowth(site.tenantId);
        String custToken = registerCustomer(site, "bob@buyer.test", "Bob Buyer");
        String pidA = seedProduct(site.tenantId, "Paracetamol", "300.00", 20, false);
        String pidB = seedProduct(site.tenantId, "Vitamin C", "500.00", 20, false);

        // Add A quantity 2.
        mockMvc.perform(post("/api/v1/public/ph-cart/pharmacy/cart")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pidA, "quantity", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.itemCount").value(2))
                .andExpect(jsonPath("$.cart.subtotal").value(600.00));

        // Upsert A to quantity 5 — REPLACE, not add.
        mockMvc.perform(post("/api/v1/public/ph-cart/pharmacy/cart")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pidA, "quantity", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.itemCount").value(5))
                .andExpect(jsonPath("$.cart.subtotal").value(1500.00));

        // Add B.
        mockMvc.perform(post("/api/v1/public/ph-cart/pharmacy/cart")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pidB, "quantity", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.itemCount").value(6));

        // Read.
        mockMvc.perform(get("/api/v1/public/ph-cart/pharmacy/cart")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.items.length()").value(2));

        // Remove A.
        mockMvc.perform(delete("/api/v1/public/ph-cart/pharmacy/cart/" + pidA)
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.itemCount").value(1));

        // Upsert exceeding stock → 400 INSUFFICIENT_STOCK.
        mockMvc.perform(post("/api/v1/public/ph-cart/pharmacy/cart")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pidB, "quantity", 999))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_STOCK"));

        // Clear.
        mockMvc.perform(delete("/api/v1/public/ph-cart/pharmacy/cart")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/api/v1/public/ph-cart/pharmacy/cart")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.itemCount").value(0));
    }

    // ========================= §6 prescriptions ==============================

    @Test
    void prescriptionSubmitAndListReturnsHistoryForOwner() throws Exception {
        Site site = bootSite("ph-rx");
        String custToken = registerCustomer(site, "carol@buyer.test", "Carol Patient");

        mockMvc.perform(post("/api/v1/public/ph-rx/pharmacy/prescriptions")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fileUrl", "https://media.conddo.io/rx/abc.jpg",
                                "patientName", "Carol Patient",
                                "prescriberName", "Dr Adeleke",
                                "notes", "5-day course please"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.prescription.id").isNotEmpty())
                .andExpect(jsonPath("$.prescription.status").value("PENDING"));

        mockMvc.perform(get("/api/v1/public/ph-rx/pharmacy/prescriptions")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prescriptions.length()").value(1))
                .andExpect(jsonPath("$.prescriptions[0].prescriberName").value("Dr Adeleke"));
    }

    // ========================= §5 checkout ===================================

    @Test
    void checkoutSucceedsDecrementsStockClearsCartAndNotifies() throws Exception {
        Site site = bootSite("ph-buy");
        upgradeToGrowth(site.tenantId);
        // Owner phone fallback for SMS notify.
        setOwnerPhone(site.tenantId, "+2348091234567");
        String custToken = registerCustomer(site, "dee@buyer.test", "Dee Customer");
        String pid = seedProduct(site.tenantId, "Paracetamol", "300.00", 5, false);
        String addressId = createAddress(site, custToken, "Lagos");
        addToCart(site, custToken, pid, 2);

        MvcResult checkout = mockMvc.perform(post("/api/v1/public/ph-buy/pharmacy/orders")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 2)),
                                "addressId", addressId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.order.id").isNotEmpty())
                // 2 × 300 = 600 subtotal + 1500 Lagos delivery = 2100 total.
                .andExpect(jsonPath("$.order.subtotal").value(600.00))
                .andExpect(jsonPath("$.order.deliveryFee").value(1500))
                .andExpect(jsonPath("$.order.total").value(2100.00))
                .andExpect(jsonPath("$.order.paymentStatus").value("PENDING"))
                .andReturn();
        String orderId = objectMapper.readTree(checkout.getResponse().getContentAsString())
                .path("order").path("id").asText();

        // Stock decremented (5 → 3).
        assertEquals(3, readStock(pid), "successful order must persist the stock decrement");

        // Cart cleared.
        mockMvc.perform(get("/api/v1/public/ph-buy/pharmacy/cart")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(jsonPath("$.cart.itemCount").value(0));

        // Notify side-effects — both channels fired.
        verify(emailSender, timeout(5_000)).send(
                eq("owner@ph-buy.test"),
                contains("New order"),
                contains("Dee Customer"));
        verify(smsSender, timeout(5_000)).send(
                eq("+2348091234567"),
                contains("Dee Customer"));

        // Customer can fetch their own order detail (includes items + address).
        mockMvc.perform(get("/api/v1/public/ph-buy/pharmacy/orders/" + orderId)
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value(orderId))
                .andExpect(jsonPath("$.order.items.length()").value(1))
                .andExpect(jsonPath("$.order.address.state").value("Lagos"));

        // …and their order list.
        mockMvc.perform(get("/api/v1/public/ph-buy/pharmacy/orders")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].id").value(orderId));
    }

    @Test
    void checkoutShortageRollsBackStockAndReturns400() throws Exception {
        Site site = bootSite("ph-short");
        upgradeToGrowth(site.tenantId);
        String custToken = registerCustomer(site, "edna@buyer.test", "Edna Buyer");
        String pid = seedProduct(site.tenantId, "VitaminC", "500.00", 1, false);
        String addressId = createAddress(site, custToken, "Lagos");

        mockMvc.perform(post("/api/v1/public/ph-short/pharmacy/orders")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 2)),
                                "addressId", addressId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.items[0].productId").value(pid))
                .andExpect(jsonPath("$.items[0].available").value(1))
                .andExpect(jsonPath("$.items[0].requested").value(2));

        // Stock unchanged (rollback held).
        assertEquals(1, readStock(pid), "shortage must roll back any stock decrement");
    }

    @Test
    void checkoutForPrescriptionItemWithoutPrescriptionReturns400() throws Exception {
        Site site = bootSite("ph-gate-rx");
        upgradeToGrowth(site.tenantId);
        String custToken = registerCustomer(site, "fred@buyer.test", "Fred Buyer");
        String pid = seedProduct(site.tenantId, "Amoxicillin", "1500.00", 10, true);
        String addressId = createAddress(site, custToken, "Lagos");

        mockMvc.perform(post("/api/v1/public/ph-gate-rx/pharmacy/orders")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "addressId", addressId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PRESCRIPTION_REQUIRED"));

        // Stock untouched.
        assertEquals(10, readStock(pid));
    }

    @Test
    void checkoutBlockedOnLauncherPlanReturns403() throws Exception {
        // No upgradeToGrowth — stays on launcher, which lacks order_management.
        Site site = bootSite("ph-launch");
        String custToken = registerCustomer(site, "gina@buyer.test", "Gina Buyer");
        String pid = seedProduct(site.tenantId, "Coughsyrup", "800.00", 5, false);
        String addressId = createAddress(site, custToken, "Lagos");

        mockMvc.perform(post("/api/v1/public/ph-launch/pharmacy/orders")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "addressId", addressId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MODULE_NOT_ENABLED"));
    }

    @Test
    void rolledBackCheckoutDoesNotNotify() throws Exception {
        Site site = bootSite("ph-noemit");
        upgradeToGrowth(site.tenantId);
        String custToken = registerCustomer(site, "hank@buyer.test", "Hank Bogus");
        String pid = seedProduct(site.tenantId, "Out-of-stock", "500.00", 1, false);
        String addressId = createAddress(site, custToken, "Lagos");

        mockMvc.perform(post("/api/v1/public/ph-noemit/pharmacy/orders")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 2)),
                                "addressId", addressId))))
                .andExpect(status().isBadRequest());

        // No AFTER_COMMIT misfire.
        Thread.sleep(500);
        verify(emailSender, never()).send(anyString(), anyString(), contains("Hank Bogus"));
        verify(smsSender, never()).send(anyString(), contains("Hank Bogus"));
    }

    @Test
    void orderDetailRefusesAnotherCustomersOrder() throws Exception {
        Site site = bootSite("ph-iso");
        upgradeToGrowth(site.tenantId);
        String tokA = registerCustomer(site, "iso-a@buyer.test", "Iso A");
        String tokB = registerCustomer(site, "iso-b@buyer.test", "Iso B");
        String pid = seedProduct(site.tenantId, "Aspirin", "200.00", 10, false);
        String addrA = createAddress(site, tokA, "Lagos");
        // A places an order.
        MvcResult res = mockMvc.perform(post("/api/v1/public/ph-iso/pharmacy/orders")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "addressId", addrA))))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("order").path("id").asText();

        // B tries to read A's order → 403.
        mockMvc.perform(get("/api/v1/public/ph-iso/pharmacy/orders/" + orderId)
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ----- helpers ----------------------------------------------------------

    /** Tenant + key + activated site + a sentinel record. */
    private record Site(String tenantId, String apiKey, String tenantToken) {
    }

    private Site bootSite(String slug) throws Exception {
        String tenantId = signup(slug, "owner@" + slug + ".test");
        String tenantToken = login(slug, "owner@" + slug + ".test");
        String apiKey = regenerateKey(tenantToken);
        activateSite(tenantId, slug);
        return new Site(tenantId, apiKey, tenantToken);
    }

    private String registerCustomer(Site site, String email, String fullName) throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/v1/public/" + slugFromKey(site) + "/auth/register")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", fullName,
                                "email", email,
                                "password", "buyerpw123"))))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(reg.getResponse().getContentAsString())
                .path("token").asText();
        assertNotNull(token);
        return token;
    }

    /** Resolve slug from the tenant id by reading the row directly. */
    private String slugFromKey(Site site) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT slug FROM tenants WHERE id = ?::uuid")) {
            ps.setString(1, site.tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String createAddress(Site site, String custToken, String state) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/public/" + slugFromKey(site) + "/customer/addresses")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "label", "Home",
                                "street", "12 Allen Ave",
                                "city", "Ikeja",
                                "state", state,
                                "isDefault", true))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("id").asText();
    }

    private void addToCart(Site site, String custToken, String pid, int qty) throws Exception {
        mockMvc.perform(post("/api/v1/public/" + slugFromKey(site) + "/pharmacy/cart")
                        .header("X-Conddo-Site-Key", site.apiKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pid, "quantity", qty))))
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
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
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
        throw new IllegalStateException("trial subscription never landed for tenant " + tenantId);
    }

    private void setOwnerPhone(String tenantId, String phone) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE tenants SET contact_phone = ? WHERE id = ?::uuid")) {
            ps.setString(1, phone);
            ps.setString(2, tenantId);
            ps.executeUpdate();
        }
    }

    private String seedProduct(String tenantId, String name, String price,
                               int stock, boolean requiresRx) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO products (tenant_id, name, sku, price, stock, "
                             + "reorder_threshold, requires_prescription, active) "
                             + "VALUES (?::uuid, ?, ?, ?::numeric, ?, 0, ?, true) "
                             + "RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, name.toUpperCase().replace(' ', '-') + "-SKU");
            ps.setString(4, price);
            ps.setInt(5, stock);
            ps.setBoolean(6, requiresRx);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private int readStock(String productId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT stock FROM products WHERE id = ?::uuid")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
