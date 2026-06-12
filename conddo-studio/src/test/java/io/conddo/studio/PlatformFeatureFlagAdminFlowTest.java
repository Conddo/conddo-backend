package io.conddo.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Studio admin beta-access review queue
 * (HANDOFF_2026-06-12b §2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PlatformFeatureFlagAdminFlowTest {

    private static final String PW = "studio-pass-123";
    private static final String ADMIN = "flagadmin@studio.test";
    private static final String LEAD = "flaglead@studio.test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conddo").withUsername("conddo_owner").withPassword("owner_password")
            .withInitScript("test-platform-tables.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("studio.jwt.secret", () -> "test-studio-secret-at-least-32-bytes-long-0123456789");
        registry.add("studio.cors.allowed-origins", () -> "http://localhost:3000");
        registry.add("studio.service.token", () -> "test-service-token");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedStaff() throws SQLException {
        String hash = new BCryptPasswordEncoder(12).encode(PW);
        try (Connection c = ownerConn()) {
            insertStaff(c, ADMIN, hash, "Flag Admin", "ADMIN");
            insertStaff(c, LEAD, hash, "Flag Lead", "TEAM_LEAD");
        }
    }

    @Test
    void adminListsPendingInterestThenGrantsThenRevokes() throws Exception {
        UUID tenantId = seedTenant("Wellspring Pharmacy", "wellspring", "pharmacy");
        seedInterest(tenantId, "cashback_loyalty");
        String adminToken = login(ADMIN);

        // Default ?status=interest returns the pending row.
        mockMvc.perform(get("/api/jobs/admin/platform/feature-flags")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].featureKey").value("cashback_loyalty"))
                .andExpect(jsonPath("$.data[0].status").value("interest"))
                .andExpect(jsonPath("$.data[0].tenantName").value("Wellspring Pharmacy"));

        // Grant flips the row to enabled + stamps grantedAt + actor.
        mockMvc.perform(post("/api/jobs/admin/platform/feature-flags/"
                                + tenantId + "/cashback_loyalty/grant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("granted"))
                .andExpect(jsonPath("$.data.grantedByName").value("Flag Admin"));

        // List with ?status=granted&featureKey=… picks the same row up.
        mockMvc.perform(get("/api/jobs/admin/platform/feature-flags")
                        .param("status", "granted").param("featureKey", "cashback_loyalty")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].tenantId").value(tenantId.toString()));

        // Revoke keeps grantedAt + sets status=revoked.
        mockMvc.perform(post("/api/jobs/admin/platform/feature-flags/"
                                + tenantId + "/cashback_loyalty/revoke")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("revoked"))
                .andExpect(jsonPath("$.data.grantedAt").exists());
    }

    @Test
    void teamLeadCanReadButNotGrant() throws Exception {
        UUID tenantId = seedTenant("Lead Pharm", "lead-pharm", "pharmacy");
        seedInterest(tenantId, "drug_programs");
        String leadToken = login(LEAD);

        mockMvc.perform(get("/api/jobs/admin/platform/feature-flags")
                        .header(HttpHeaders.AUTHORIZATION, bearer(leadToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/jobs/admin/platform/feature-flags/"
                                + tenantId + "/drug_programs/grant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(leadToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void grantWithoutPriorInterestRowSeedsAndEnables() throws Exception {
        UUID tenantId = seedTenant("Eager Co", "eager", "pharmacy");
        String adminToken = login(ADMIN);

        // No interest row exists yet — direct grant seeds one as enabled=true.
        mockMvc.perform(post("/api/jobs/admin/platform/feature-flags/"
                                + tenantId + "/emr_basic/grant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("granted"));
    }

    // ----- helpers ----------------------------------------------------------

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PW))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private UUID seedTenant(String name, String slug, String vertical) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = ownerConn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO public.tenants (id, name, slug, vertical_id, plan_id, status) "
                             + "VALUES (?, ?, ?, ?, 'growth', 'ACTIVE')")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, slug);
            ps.setString(4, vertical);
            ps.executeUpdate();
        }
        return id;
    }

    private void seedInterest(UUID tenantId, String featureKey) throws SQLException {
        try (Connection c = ownerConn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO public.tenant_feature_flags "
                             + "(tenant_id, feature_key, status, interest, interest_at) "
                             + "VALUES (?, ?, 'beta', true, now())")) {
            ps.setObject(1, tenantId);
            ps.setString(2, featureKey);
            ps.executeUpdate();
        }
    }

    private static void insertStaff(Connection c, String email, String hash, String name,
                                    String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO studio.staff (email, password_hash, full_name, role, skills) "
                        + "VALUES (?, ?, ?, ?, '[]'::jsonb) ON CONFLICT (email) DO NOTHING")) {
            ps.setString(1, email);
            ps.setString(2, hash);
            ps.setString(3, name);
            ps.setString(4, role);
            ps.executeUpdate();
        }
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
