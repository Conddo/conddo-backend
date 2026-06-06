package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.payments.PaymentsGateway;
import io.conddo.core.studio.StudioJobGateway;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SOCIAL_AND_CREATIVE_SERVICES_SPEC §5 — proves the creative-services flow
 * end-to-end on a fully booted app + Postgres + Flyway V1–V29. PaymentsGateway
 * and StudioJobGateway are stubbed via {@code @MockBean} so the tests don't
 * depend on conddo-payments / conddo-studio being reachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CreativeServicesFlowTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";
    private static final String PASSWORD = "password123";
    private static final String STUDIO_SERVICE_TOKEN = "test-studio-token";
    private static final String PAYMENTS_SERVICE_TOKEN = "test-payments-token";

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
        registry.add("studio.service-token", () -> STUDIO_SERVICE_TOKEN);
        registry.add("payments.service-token", () -> PAYMENTS_SERVICE_TOKEN);
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
    private PaymentsGateway paymentsGateway;
    @MockBean
    private StudioJobGateway studioJobGateway;

    // ----- catalog -----------------------------------------------------------

    @Test
    void offeringsListsTheSeededCatalog() throws Exception {
        signup("ph-cat", "owner@ph-cat.test");
        String token = login("ph-cat", "owner@ph-cat.test");

        MvcResult result = mockMvc.perform(get("/api/v1/creative-services/offerings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertTrue(data.isArray() && data.size() >= 5,
                "expected ≥5 seeded offerings; got " + data);
        boolean hasStatic = false;
        for (JsonNode offering : data) {
            if ("design_static".equals(offering.path("code").asText())) {
                hasStatic = true;
                assertEquals("CREATIVE_DESIGN", offering.path("jobType").asText());
                assertTrue(offering.path("priceKobo").asInt() > 0);
            }
        }
        assertTrue(hasStatic, "expected design_static in the catalog: " + data);
    }

    // ----- create request ----------------------------------------------------

    @Test
    void createRequestPersistsPendingPaymentAndReturnsCheckoutUrl() throws Exception {
        when(paymentsGateway.initCreativeServiceCharge(
                any(), any(), any(), any(), any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-creative-1", "https://routepay.test/checkout/RP-creative-1", "PENDING")));

        String tenantId = signup("ph-req", "owner@ph-req.test");
        String token = login("ph-req", "owner@ph-req.test");

        MvcResult result = mockMvc.perform(post("/api/v1/creative-services/requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offeringCode", "design_static",
                                "brief", "Launch announcement post for Instagram"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.checkoutUrl").value(
                        "https://routepay.test/checkout/RP-creative-1"))
                .andExpect(jsonPath("$.data.request.status").value("pending_payment"))
                .andExpect(jsonPath("$.data.request.offering.code").value("design_static"))
                .andExpect(jsonPath("$.data.request.priceKobo").value(500000))
                .andReturn();

        // The payment reference is pinned to the row for the webhook to find.
        String requestId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("request").path("id").asText();
        assertEquals("RP-creative-1", readPaymentReference(requestId));
    }

    @Test
    void unknownOfferingCodeIs404() throws Exception {
        signup("ph-unknown", "owner@ph-unknown.test");
        String token = login("ph-unknown", "owner@ph-unknown.test");

        mockMvc.perform(post("/api/v1/creative-services/requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offeringCode", "design_phantom",
                                "brief", "Whatever"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void paymentsUnreachableReturns503AndDoesNotLeaveOrphanRow() throws Exception {
        when(paymentsGateway.initCreativeServiceCharge(
                any(), any(), any(), any(), any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        String tenantId = signup("ph-503", "owner@ph-503.test");
        String token = login("ph-503", "owner@ph-503.test");

        mockMvc.perform(post("/api/v1/creative-services/requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offeringCode", "design_static",
                                "brief", "Anything"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("PAYMENTS_UNAVAILABLE"));

        // Transaction rolled back — no orphan creative_service_requests row.
        assertEquals(0, countRequests(tenantId));
    }

    // ----- paid webhook ------------------------------------------------------

    @Test
    void paymentsCallbackFlipsToQueuedAndCreatesStudioJob() throws Exception {
        when(paymentsGateway.initCreativeServiceCharge(
                any(), any(), any(), any(), any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-paid-1", "https://routepay.test/checkout/RP-paid-1", "PENDING")));
        UUID studioJobId = UUID.randomUUID();
        when(studioJobGateway.createJob(any(), eq("CREATIVE_DESIGN"), anyString(), any()))
                .thenReturn(Optional.of(new StudioJobGateway.StudioJobRef(
                        studioJobId, "CD-1042", "QUEUED")));

        String tenantId = signup("ph-paid", "owner@ph-paid.test");
        String token = login("ph-paid", "owner@ph-paid.test");
        String requestId = createRequest(token, "design_static", "Brief A");

        UUID paymentId = UUID.randomUUID();
        Map<String, Object> notify = Map.of(
                "tenantId", tenantId,
                "paymentId", paymentId,
                "status", "PAID",
                "creativeRequestId", requestId,
                "paymentReference", "RP-paid-1",
                "amountKobo", 500000);
        mockMvc.perform(post("/api/v1/internal/payments/notify")
                        .header("X-Payments-Service-Token", PAYMENTS_SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notify)))
                .andExpect(status().isOk());

        // Row is now queued + Studio job pinned.
        assertEquals("queued", readStatus(requestId));
        assertEquals(studioJobId.toString(), readStudioJobId(requestId));
        verify(studioJobGateway, times(1)).createJob(any(), eq("CREATIVE_DESIGN"), anyString(), any());
    }

    @Test
    void paymentsCallbackBadTokenIs401() throws Exception {
        Map<String, Object> notify = Map.of(
                "tenantId", UUID.randomUUID(),
                "paymentId", UUID.randomUUID(),
                "status", "PAID",
                "paymentReference", "anything",
                "amountKobo", 0);
        mockMvc.perform(post("/api/v1/internal/payments/notify")
                        .header("X-Payments-Service-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notify)))
                .andExpect(status().isUnauthorized());
    }

    // ----- delivered webhook -------------------------------------------------

    @Test
    void studioDeliveredCallbackFlipsToDeliveredAndStampsMedia() throws Exception {
        when(paymentsGateway.initCreativeServiceCharge(
                any(), any(), any(), any(), any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-deliv-1", "https://routepay.test/checkout/RP-deliv-1", "PENDING")));
        when(studioJobGateway.createJob(any(), anyString(), anyString(), any()))
                .thenReturn(Optional.of(new StudioJobGateway.StudioJobRef(
                        UUID.randomUUID(), "CD-2001", "QUEUED")));

        String tenantId = signup("ph-deliv", "owner@ph-deliv.test");
        String token = login("ph-deliv", "owner@ph-deliv.test");
        String requestId = createRequest(token, "design_static", "Brief D");

        // Walk to queued first via the paid webhook so the lifecycle is realistic.
        mockMvc.perform(post("/api/v1/internal/payments/notify")
                        .header("X-Payments-Service-Token", PAYMENTS_SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId,
                                "paymentId", UUID.randomUUID(),
                                "status", "PAID",
                                "creativeRequestId", requestId,
                                "paymentReference", "RP-deliv-1",
                                "amountKobo", 500000))))
                .andExpect(status().isOk());

        // Studio fires delivered.
        Map<String, Object> body = Map.of(
                "media", List.of(
                        Map.of("url", "https://cdn.test/design-1.png", "width", 1080, "height", 1080)));
        mockMvc.perform(post("/api/v1/internal/creative-services/" + requestId + "/delivered")
                        .header("X-Studio-Service-Token", STUDIO_SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        assertEquals("delivered", readStatus(requestId));
        assertTrue(readDeliveryMedia(requestId).contains("design-1.png"));
    }

    @Test
    void studioDeliveredBadTokenIs401() throws Exception {
        mockMvc.perform(post("/api/v1/internal/creative-services/"
                        + UUID.randomUUID() + "/delivered")
                        .header("X-Studio-Service-Token", "nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"media\":[{\"url\":\"x\"}]}"))
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

    private String createRequest(String token, String offeringCode, String brief) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/creative-services/requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offeringCode", offeringCode, "brief", brief))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("request").path("id").asText();
    }

    private String readStatus(String requestId) throws SQLException {
        return readOne("SELECT status FROM creative_service_requests WHERE id = ?::uuid", requestId);
    }

    private String readStudioJobId(String requestId) throws SQLException {
        return readOne("SELECT studio_job_id FROM creative_service_requests WHERE id = ?::uuid", requestId);
    }

    private String readPaymentReference(String requestId) throws SQLException {
        return readOne("SELECT payment_reference FROM creative_service_requests WHERE id = ?::uuid", requestId);
    }

    private String readDeliveryMedia(String requestId) throws SQLException {
        return readOne("SELECT delivery_media::text FROM creative_service_requests WHERE id = ?::uuid", requestId);
    }

    private int countRequests(String tenantId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT count(*) FROM creative_service_requests WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String readOne(String sql, String id) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected one row");
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
