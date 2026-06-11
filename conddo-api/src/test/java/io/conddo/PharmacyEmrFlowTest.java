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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Roadmap Beta 4 — Basic EMR.
 *
 * <ul>
 *   <li>Feature gate refuses without flag.</li>
 *   <li>404 before EMR is created (FE shows empty demographics).</li>
 *   <li>POST/PUT upsert with full demographics + JSONB arrays.</li>
 *   <li>Note immutability — no UPDATE/DELETE on a single note.</li>
 *   <li>Document upload via ObjectStorage (mocked) + list.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyEmrFlowTest {

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
    private ObjectStorage objectStorage;

    @Test
    void featureGateRefusesWithoutFlag() throws Exception {
        signup("emr-gate", "owner@emr-gate.test");
        String token = login("emr-gate", "owner@emr-gate.test");
        mockMvc.perform(get("/api/v1/pharmacy/emr/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FEATURE_NOT_ENABLED"))
                .andExpect(jsonPath("$.error.details[0].message").value("emr_basic"));
    }

    @Test
    void fullLifecycleCreateUpdateNoteAndDocument() throws Exception {
        when(objectStorage.put(anyString(), anyString(), anyLong(), any()))
                .thenReturn(new ObjectStorage.Stored(
                        "pharmacy/emr/xyz/123",
                        "https://cdn.conddo.io/pharmacy/emr/xyz/123.pdf"));

        String tenantId = signup("emr-flow", "owner@emr-flow.test");
        String token = login("emr-flow", "owner@emr-flow.test");
        grantFeature(tenantId, "emr_basic");
        UUID customerId = UUID.randomUUID();

        // GET before create → 404 (FE shows empty demographics).
        mockMvc.perform(get("/api/v1/pharmacy/emr/" + customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());

        // Create — full demographics + a couple of JSONB rows.
        mockMvc.perform(post("/api/v1/pharmacy/emr/" + customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bloodGroup", "O+",
                                "genotype", "AA",
                                "heightCm", 170,
                                "weightKg", 68,
                                "allergies", List.of(Map.of(
                                        "substance", "Penicillin", "severity", "severe")),
                                "chronicConditions", List.of(Map.of(
                                        "name", "Hypertension", "status", "active"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bloodGroup").value("O+"))
                .andExpect(jsonPath("$.data.allergies.length()").value(1));

        // GET returns the EMR + notes (empty array).
        mockMvc.perform(get("/api/v1/pharmacy/emr/" + customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bloodGroup").value("O+"))
                .andExpect(jsonPath("$.data.notes").isArray())
                .andExpect(jsonPath("$.data.notes.length()").value(0));

        // Update demographics — PUT keeps the existing row.
        mockMvc.perform(put("/api/v1/pharmacy/emr/" + customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "weightKg", 70.5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weightKg").value(70.5))
                .andExpect(jsonPath("$.data.bloodGroup").value("O+"));

        // Add an immutable note.
        MvcResult notedRes = mockMvc.perform(post("/api/v1/pharmacy/emr/" + customerId + "/notes")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "note", "Patient reports rash 30 min after first dose of amoxicillin.",
                                "noteType", "ALLERGY"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.note").value(
                        "Patient reports rash 30 min after first dose of amoxicillin."))
                .andExpect(jsonPath("$.data.noteType").value("ALLERGY"))
                .andReturn();
        String noteId = objectMapper.readTree(notedRes.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Note immutability — DELETE on the individual note path is not
        // registered. Whatever the response code is, the note must
        // still be there on the next GET.
        mockMvc.perform(delete("/api/v1/pharmacy/emr/" + customerId + "/notes/" + noteId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)));
        mockMvc.perform(get("/api/v1/pharmacy/emr/" + customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes.length()").value(1))
                .andExpect(jsonPath("$.data.notes[0].id").value(noteId));

        // Upload a document.
        MockMultipartFile file = new MockMultipartFile("file",
                "fbc.pdf", "application/pdf", "fake-pdf-bytes".getBytes());
        mockMvc.perform(multipart("/api/v1/pharmacy/emr/" + customerId + "/documents")
                        .file(file)
                        .param("docType", "LAB_RESULT")
                        .param("label", "FBC 8 Jun")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.docType").value("LAB_RESULT"))
                .andExpect(jsonPath("$.data.label").value("FBC 8 Jun"))
                .andExpect(jsonPath("$.data.fileUrl")
                        .value("https://cdn.conddo.io/pharmacy/emr/xyz/123.pdf"));

        // List documents — one row.
        mockMvc.perform(get("/api/v1/pharmacy/emr/" + customerId + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].docType").value("LAB_RESULT"));
    }

    @Test
    void rejectsInvalidNoteAndDocumentTypes() throws Exception {
        String tenantId = signup("emr-bad", "owner@emr-bad.test");
        String token = login("emr-bad", "owner@emr-bad.test");
        grantFeature(tenantId, "emr_basic");
        UUID customerId = UUID.randomUUID();

        // Create the EMR so we can hit note + doc validation.
        mockMvc.perform(post("/api/v1/pharmacy/emr/" + customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bloodGroup", "A+"))))
                .andExpect(status().isCreated());

        // Invalid noteType → 409 via IllegalArgumentException handler.
        mockMvc.perform(post("/api/v1/pharmacy/emr/" + customerId + "/notes")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "note", "x", "noteType", "INVALID_TYPE"))))
                .andExpect(status().isConflict());

        // Invalid docType.
        MockMultipartFile file = new MockMultipartFile("file",
                "x.pdf", "application/pdf", "x".getBytes());
        mockMvc.perform(multipart("/api/v1/pharmacy/emr/" + customerId + "/documents")
                        .file(file)
                        .param("docType", "NOT_A_TYPE")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict());

        // Wrong content-type (text/plain) → 409.
        MockMultipartFile text = new MockMultipartFile("file",
                "x.txt", "text/plain", "x".getBytes());
        mockMvc.perform(multipart("/api/v1/pharmacy/emr/" + customerId + "/documents")
                        .file(text)
                        .param("docType", "OTHER")
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

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
