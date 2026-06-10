package io.conddo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.storage.ObjectStorage;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHARMACY_PUBLIC_API_SPEC §10 — public-side upload. Site-key + customer
 * JWT, multipart payload in, permanent CDN URL out. Mocks
 * {@link ObjectStorage} (the real impl is Cloudinary) so the test runs
 * without an external network call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PublicUploadFlowTest {

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
    @MockBean
    private ObjectStorage objectStorage;

    @Test
    void customerUploadsPrescriptionImageAndUsesUrlOnSubmit() throws Exception {
        when(objectStorage.put(anyString(), anyString(), anyLong(), any()))
                .thenReturn(new ObjectStorage.Stored(
                        "ph-up/123-prescription",
                        "https://cdn.conddo.io/ph-up/123-prescription.jpg"));

        String tenantId = signup("ph-up", "owner@ph-up.test");
        String tenantToken = login("ph-up", "owner@ph-up.test");
        String apiKey = regenerateKey(tenantToken);
        activateSite(tenantId, "ph-up");
        String custToken = registerCustomer(apiKey, "ph-up",
                "buyer@ph-up.test", "Buyer Up");

        // Customer uploads a prescription image.
        MockMultipartFile file = new MockMultipartFile("file",
                "rx.jpg", "image/jpeg", "fake-jpeg-bytes".getBytes());
        MvcResult uploadRes = mockMvc.perform(multipart("/api/v1/public/ph-up/upload")
                        .file(file)
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.fileUrl").value(
                        "https://cdn.conddo.io/ph-up/123-prescription.jpg"))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andReturn();
        String fileUrl = objectMapper.readTree(uploadRes.getResponse().getContentAsString())
                .path("fileUrl").asText();

        // And then submits the prescription with that URL.
        mockMvc.perform(post("/api/v1/public/ph-up/pharmacy/prescriptions")
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fileUrl", fileUrl,
                                "patientName", "Buyer Up",
                                "prescriberName", "Dr Adeleke",
                                "notes", "5-day course"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.prescription.status").value("PENDING"));

        // The MediaAsset row is tagged with the customer's id as uploaded_by.
        assertTrue(prescriptionUploadedByCustomer(tenantId, fileUrl),
                "media row should carry the customer as uploaded_by");
    }

    @Test
    void uploadWithoutCustomerJwtReturns401() throws Exception {
        String tenantId = signup("ph-up-noauth", "owner@ph-up-noauth.test");
        String tenantToken = login("ph-up-noauth", "owner@ph-up-noauth.test");
        String apiKey = regenerateKey(tenantToken);
        activateSite(tenantId, "ph-up-noauth");

        MockMultipartFile file = new MockMultipartFile("file",
                "rx.jpg", "image/jpeg", "x".getBytes());
        // Site key valid, but no customer JWT — should be 401 UNAUTHENTICATED.
        mockMvc.perform(multipart("/api/v1/public/ph-up-noauth/upload")
                        .file(file)
                        .header("X-Conddo-Site-Key", apiKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
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

    private String registerCustomer(String apiKey, String slug, String email, String fullName) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/public/" + slug + "/auth/register")
                        .header("X-Conddo-Site-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", fullName,
                                "email", email,
                                "password", "buyerpw123"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("token").asText();
    }

    private boolean prescriptionUploadedByCustomer(String tenantId, String fileUrl) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT uploaded_by FROM media_assets WHERE tenant_id = ?::uuid AND url = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, fileUrl);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "media row must exist");
                return rs.getObject("uploaded_by") != null;
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
