package io.conddo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.ai.AnthropicGateway;
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

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI module suggestion flow (Vertical Inference Phase C). The
 * Anthropic gateway is mocked; tests verify the prompt → response
 * → ranked-list pipeline and the error mapping when Anthropic
 * returns unparseable output.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ModuleSuggestionFlowTest {

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
    @MockBean
    private AnthropicGateway anthropicGateway;

    @Test
    void claudeReturnsRankedScoresParsedAndReturnedRecommended() throws Exception {
        signup("ai-pharm", "owner@ai-pharm.test", "pharmacy");
        String token = login("ai-pharm", "owner@ai-pharm.test");

        when(anthropicGateway.chatText(anyString())).thenReturn(
                "Here are the scores you asked for:\n"
                        + "{\"scores\":["
                        + "{\"id\":\"prescriptions\",\"confidence\":0.95,\"reason\":\"clinical surface\"},"
                        + "{\"id\":\"inventory.pharmacy\",\"confidence\":0.92,\"reason\":\"stock mgmt\"},"
                        + "{\"id\":\"pos.pharmacy\",\"confidence\":0.88,\"reason\":\"in-store sales\"},"
                        + "{\"id\":\"music-school\",\"confidence\":0.02,\"reason\":\"unrelated\"}"
                        + "]}");

        mockMvc.perform(post("/api/v1/tenant/modules/suggest")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "businessDescription",
                                "We sell prescription medications, run in-store consultations, "
                                        + "and have a small online shop."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scores[0].id").value("prescriptions"))
                .andExpect(jsonPath("$.data.scores[0].confidence").value(0.95))
                .andExpect(jsonPath("$.data.recommended.length()").value(3))
                .andExpect(jsonPath("$.data.recommended[2].id").value("pos.pharmacy"));
    }

    @Test
    void verticalHintBiasesPromptAndAnthropicResponseStillParses() throws Exception {
        signup("ai-hint", "owner@ai-hint.test", "general");
        String token = login("ai-hint", "owner@ai-hint.test");

        when(anthropicGateway.chatText(anyString())).thenReturn(
                "{\"scores\":["
                        + "{\"id\":\"fittings.fashion\",\"confidence\":0.91,\"reason\":\"tailor shop\"},"
                        + "{\"id\":\"fabric.fashion\",\"confidence\":0.85,\"reason\":\"sourcing\"}"
                        + "]}");

        mockMvc.perform(post("/api/v1/tenant/modules/suggest")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "businessDescription", "Bespoke tailoring shop in Lagos.",
                                "verticalHint", "fashion"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scores[0].id").value("fittings.fashion"));
    }

    @Test
    void unparseableAnthropicResponseReturns502AiSuggestionFailed() throws Exception {
        signup("ai-bad", "owner@ai-bad.test", "pharmacy");
        String token = login("ai-bad", "owner@ai-bad.test");

        when(anthropicGateway.chatText(anyString())).thenReturn("not json at all, sorry");

        mockMvc.perform(post("/api/v1/tenant/modules/suggest")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "businessDescription", "Tiny corner pharmacy."))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("AI_SUGGESTION_FAILED"));
    }

    // ----- helpers ----------------------------------------------------------

    private void signup(String slug, String adminEmail, String verticalId) throws Exception {
        mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Biz", "slug", slug, "verticalId", verticalId,
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

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
