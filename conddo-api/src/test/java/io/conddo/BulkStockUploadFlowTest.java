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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bulk stock upload (Pharmacy Spec v2 supplemental) — seeds the POS
 * with ground-truth inventory. Covers:
 *
 * <ul>
 *   <li>New SKU → product row + RESTOCK movement</li>
 *   <li>Existing SKU → stock set absolute, ADJUSTMENT movement, field updates apply</li>
 *   <li>Row-level errors don't poison the whole upload</li>
 *   <li>Dry-run returns the same summary shape without touching the DB</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BulkStockUploadFlowTest {

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
    void uploadCreatesNewSkusAndUpdatesExistingOnesAndLogsMovements() throws Exception {
        String tenantId = signup("bulk-real", "owner@bulk-real.test");
        String token = login("bulk-real", "owner@bulk-real.test");
        // Existing SKU PARA-500 with stock 10 — should be set absolute to 80.
        String existingId = seedProduct(tenantId, "Paracetamol 500mg", "PARA-500", 10);

        String csv = String.join("\n",
                "sku,name,price,stock,reorder_threshold,batch_number,expiry_date",
                "PARA-500,Paracetamol 500mg,150,80,15,B-2026-04,2027-04-30",
                // New SKU — creates a product
                "AMOX-250,Amoxicillin 250mg,420,45,10,B-2026-05,2027-05-31",
                // Another new SKU — minimal fields
                "VITC-1G,Vitamin C 1g,200,200,,,");

        MvcResult result = mockMvc.perform(multipart("/api/v1/inventory/bulk-upload")
                        .file(new MockMultipartFile("file", "stock.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.totalRows").value(3))
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.skipped").value(0))
                .andReturn();

        // Existing product was set absolute to 80 (delta +70 logged as ADJUSTMENT).
        assertEquals(80, readStock(existingId), "Existing SKU stock should be set to CSV value");
        assertEquals("B-2026-04", readBatch(existingId), "Existing SKU batch should be updated");
        // ADJUSTMENT movement is on the audit trail.
        assertTrue(hasMovement(existingId, "ADJUSTMENT", 70, 10, 80),
                "Existing SKU should have an ADJUSTMENT movement +70");

        // New SKUs created.
        String amoxId = findProductIdBySku(tenantId, "AMOX-250");
        assertNotNull(amoxId, "New SKU AMOX-250 should have been created");
        assertEquals(45, readStock(amoxId));
        assertTrue(hasMovement(amoxId, "RESTOCK", 45, 0, 45),
                "New SKU should have a RESTOCK movement for the initial stock");

        String vitcId = findProductIdBySku(tenantId, "VITC-1G");
        assertNotNull(vitcId);
        assertEquals(200, readStock(vitcId));
    }

    @Test
    void rowLevelErrorsAreReportedAndDoNotPoisonTheWholeUpload() throws Exception {
        String tenantId = signup("bulk-err", "owner@bulk-err.test");
        String token = login("bulk-err", "owner@bulk-err.test");

        String csv = String.join("\n",
                "sku,name,stock",
                // Valid — creates a product
                "GOOD-1,Good Product,30",
                // Invalid — stock not a number
                "BAD-1,Bad Product,not-a-number",
                // Invalid — missing name on a new SKU
                "ORPHAN-1,,12",
                // Valid — creates another
                "GOOD-2,Another Good,5");

        mockMvc.perform(multipart("/api/v1/inventory/bulk-upload")
                        .file(new MockMultipartFile("file", "stock.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(4))
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.skipped").value(2))
                .andExpect(jsonPath("$.data.errors.length()").value(2));

        // The two good rows still landed in the DB.
        assertNotNull(findProductIdBySku(tenantId, "GOOD-1"));
        assertNotNull(findProductIdBySku(tenantId, "GOOD-2"));
    }

    @Test
    void dryRunReturnsSummaryWithoutPersisting() throws Exception {
        String tenantId = signup("bulk-dry", "owner@bulk-dry.test");
        String token = login("bulk-dry", "owner@bulk-dry.test");
        String existingId = seedProduct(tenantId, "Loratadine", "LORA-10", 5);

        String csv = String.join("\n",
                "sku,name,stock",
                "LORA-10,Loratadine,99",
                "BRAND-NEW,Brand New Drug,42");

        mockMvc.perform(multipart("/api/v1/inventory/bulk-upload")
                        .file(new MockMultipartFile("file", "stock.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("dryRun", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.totalRows").value(2))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.updated").value(1));

        // Crucial: the DB didn't change.
        assertEquals(5, readStock(existingId), "Dry-run must not alter existing stock");
        assertEquals(null, findProductIdBySku(tenantId, "BRAND-NEW"),
                "Dry-run must not create new products");
    }

    // ----- helpers ----------------------------------------------------------

    private String signup(String slug, String adminEmail) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Pharmacy", "slug", slug,
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

    private String seedProduct(String tenantId, String name, String sku, int stock) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO products (tenant_id, name, sku, price, stock, "
                             + "reorder_threshold, active) "
                             + "VALUES (?::uuid, ?, ?, 100, ?, 0, true) RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, sku);
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

    private String readBatch(String productId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT batch_number FROM products WHERE id = ?::uuid")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String findProductIdBySku(String tenantId, String sku) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT id FROM products WHERE tenant_id = ?::uuid AND sku = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, sku);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private boolean hasMovement(String productId, String type, int delta, int before, int after)
            throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT 1 FROM pharmacy_stock_movements "
                             + "WHERE product_id = ?::uuid AND movement_type = ? "
                             + "AND quantity_change = ? AND quantity_before = ? AND quantity_after = ?")) {
            ps.setString(1, productId);
            ps.setString(2, type);
            ps.setInt(3, delta);
            ps.setInt(4, before);
            ps.setInt(5, after);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
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
