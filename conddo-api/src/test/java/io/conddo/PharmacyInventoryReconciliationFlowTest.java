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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Module Spec v2 §12A — Inventory Reconciliation end-to-end:
 * <ul>
 *   <li>Restock → SALE-side movement log appears with quantityBefore/After</li>
 *   <li>Manual adjustment by absolute target → ADJUSTMENT movement</li>
 *   <li>Reconciliation start → fills items with system snapshot</li>
 *   <li>Submit counts → variance calculated</li>
 *   <li>Complete → variances applied as RECONCILIATION movements, product stock updated</li>
 *   <li>Listing endpoint with filters returns the right rows</li>
 * </ul>
 *
 * <p>Reactor tests don't have Redis on localhost — the publisher
 * catches the connection failure and logs a warning. The stock writes
 * commit either way, which is the user-visible behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyInventoryReconciliationFlowTest {

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
        // Fast-fail Redis connect — there's no Redis on localhost in CI/test.
        // Without this, every stock event publish would block for the
        // Lettuce default 10s connect timeout.
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
    void restockLogsMovementsAndPostMovementsListReflectsThem() throws Exception {
        String tenantId = signup("inv-restock", "owner@inv-restock.test");
        String token = login("inv-restock", "owner@inv-restock.test");
        String pid = seedProduct(tenantId, "Paracetamol", 10);

        MvcResult result = mockMvc.perform(post("/api/v1/inventory/restock")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 50)),
                                "note", "Emzor monthly restock"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.itemsRestocked").value(1))
                .andExpect(jsonPath("$.data.movements[0].movementType").value("RESTOCK"))
                .andExpect(jsonPath("$.data.movements[0].quantityChange").value(50))
                .andExpect(jsonPath("$.data.movements[0].quantityBefore").value(10))
                .andExpect(jsonPath("$.data.movements[0].quantityAfter").value(60))
                .andReturn();

        // Stock landed in the products table.
        assertEquals(60, readStock(pid), "RESTOCK movement must update products.stock");

        // Movement log surfaces the new row.
        mockMvc.perform(get("/api/v1/inventory/movements")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].movementType").value("RESTOCK"));
    }

    @Test
    void adjustmentEndpointSetsAbsoluteValueAndLogsVariance() throws Exception {
        String tenantId = signup("inv-adjust", "owner@inv-adjust.test");
        String token = login("inv-adjust", "owner@inv-adjust.test");
        String pid = seedProduct(tenantId, "Vitamin C", 57);

        mockMvc.perform(post("/api/v1/inventory/adjustment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pid,
                                "adjustedQty", 45,
                                "reason", "EXPIRY_REMOVAL",
                                "note", "12 units expired — batch removed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.movement.movementType").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.data.quantityBefore").value(57))
                .andExpect(jsonPath("$.data.quantityAfter").value(45))
                .andExpect(jsonPath("$.data.variance").value(-12));

        assertEquals(45, readStock(pid));
    }

    @Test
    void reconciliationLifecycleStartCountAndCompleteAppliesVariances() throws Exception {
        String tenantId = signup("inv-rec", "owner@inv-rec.test");
        String token = login("inv-rec", "owner@inv-rec.test");
        String matchPid = seedProduct(tenantId, "Aspirin", 30);
        String shortPid = seedProduct(tenantId, "Cough Syrup", 20);

        // Start session — items per active product snapshotted as system_qty.
        MvcResult started = mockMvc.perform(post("/api/v1/inventory/reconciliations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("note", "June physical count"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalProducts").value(2))
                .andReturn();
        String reconcId = objectMapper.readTree(started.getResponse().getContentAsString())
                .path("data").path("reconciliationId").asText();

        // GET shows two items at system_qty=30 and 20, no count yet.
        mockMvc.perform(get("/api/v1/inventory/reconciliations/" + reconcId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reconciliation.items.length()").value(2));

        // Submit counts: one matches, one is short by 3.
        mockMvc.perform(patch("/api/v1/inventory/reconciliations/" + reconcId + "/counts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "counts", List.of(
                                        Map.of("productId", matchPid, "countedQty", 30),
                                        Map.of("productId", shortPid, "countedQty", 17))))))
                .andExpect(status().isOk());

        // Confirm variance was recorded per item.
        MvcResult loaded = mockMvc.perform(get("/api/v1/inventory/reconciliations/" + reconcId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(loaded.getResponse().getContentAsString())
                .path("data").path("reconciliation").path("items");
        int matchVar = 0, shortVar = 0;
        for (JsonNode item : items) {
            if (item.path("productId").asText().equals(matchPid)) {
                matchVar = item.path("variance").asInt();
            }
            if (item.path("productId").asText().equals(shortPid)) {
                shortVar = item.path("variance").asInt();
            }
        }
        assertEquals(0, matchVar);
        assertEquals(-3, shortVar);

        // Complete — variances applied as RECONCILIATION movements, product
        // stock updated. Matched row is a no-op, shortPid drops by 3 in stock.
        mockMvc.perform(post("/api/v1/inventory/reconciliations/" + reconcId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalProducts").value(2))
                .andExpect(jsonPath("$.data.summary.matched").value(1))
                .andExpect(jsonPath("$.data.summary.variance").value(1))
                .andExpect(jsonPath("$.data.summary.totalVarianceUnits").value(-3))
                .andExpect(jsonPath("$.data.summary.adjustmentsApplied").value(1));

        assertEquals(30, readStock(matchPid), "matched row stays unchanged");
        assertEquals(17, readStock(shortPid), "varied row updated to counted qty");

        // The RECONCILIATION movement appears in the log alongside any earlier ones.
        mockMvc.perform(get("/api/v1/inventory/movements")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("movementType", "RECONCILIATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].quantityChange").value(-3))
                .andExpect(jsonPath("$.data[0].referenceKind").value("RECONCILIATION"));
    }

    @Test
    void completingTwiceIsRejected() throws Exception {
        String tenantId = signup("inv-twice", "owner@inv-twice.test");
        String token = login("inv-twice", "owner@inv-twice.test");
        seedProduct(tenantId, "Iron Tablets", 10);
        MvcResult started = mockMvc.perform(post("/api/v1/inventory/reconciliations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("note", "Twice"))))
                .andExpect(status().isCreated())
                .andReturn();
        String reconcId = objectMapper.readTree(started.getResponse().getContentAsString())
                .path("data").path("reconciliationId").asText();

        mockMvc.perform(post("/api/v1/inventory/reconciliations/" + reconcId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        // Second complete → 409 (the session is no longer IN_PROGRESS).
        mockMvc.perform(post("/api/v1/inventory/reconciliations/" + reconcId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict());
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

    private String seedProduct(String tenantId, String name, int stock) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO products (tenant_id, name, sku, price, stock, "
                             + "reorder_threshold, active) "
                             + "VALUES (?::uuid, ?, ?, 100, ?, 0, true) RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, name.toUpperCase().replace(' ', '-') + "-SKU");
            ps.setInt(4, stock);
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

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
