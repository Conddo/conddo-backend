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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-tenant module opt-in (Vertical Inference Phase B). A pharmacy
 * tenant can opt INTO a fashion-only module; a tenant can opt OUT of
 * a module that's in their vertical default. Changes take effect on
 * the next login (the JWT activeModules claim).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TenantModulesFlowTest {

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
    void pharmacyTenantOptsIntoFashionModuleAndItLandsOnNextLoginsJwt() throws Exception {
        signupPharmacy("opt-in-pharm", "owner@opt-in.test");
        String token = login("opt-in-pharm", "owner@opt-in.test");

        // Initial JWT does NOT carry "fittings.fashion".
        assertFalse(modulesFromJwt(token).contains("fittings.fashion"),
                "pharmacy default should not include fittings.fashion");

        // Owner opts in.
        mockMvc.perform(post("/api/v1/tenant/modules/fittings.fashion/enable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        // Re-login — the new JWT carries it.
        String token2 = login("opt-in-pharm", "owner@opt-in.test");
        assertTrue(modulesFromJwt(token2).contains("fittings.fashion"),
                "opt-in must surface on the next login's activeModules claim");
    }

    @Test
    void pharmacyTenantOptsOutOfDefaultModuleAndItDisappearsOnNextLogin() throws Exception {
        signupPharmacy("opt-out-pharm", "owner@opt-out.test");
        String token = login("opt-out-pharm", "owner@opt-out.test");

        assertTrue(modulesFromJwt(token).contains("consultations"),
                "pharmacy default should include consultations");

        mockMvc.perform(post("/api/v1/tenant/modules/consultations/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        String token2 = login("opt-out-pharm", "owner@opt-out.test");
        assertFalse(modulesFromJwt(token2).contains("consultations"),
                "opt-out must remove the module from the next login's activeModules claim");
    }

    @Test
    void listReturnsEveryKnownModuleWithItsCurrentState() throws Exception {
        signupPharmacy("list-modules", "owner@list-modules.test");
        String token = login("list-modules", "owner@list-modules.test");

        MvcResult result = mockMvc.perform(get("/api/v1/tenant/modules")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertTrue(data.size() > 10, "list should include the union of every vertical's modules");
        boolean foundPharmacyDefault = false;
        for (JsonNode row : data) {
            if ("consultations".equals(row.path("id").asText())) {
                assertTrue(row.path("enabled").asBoolean());
                assertTrue(row.path("inVerticalDefault").asBoolean());
                foundPharmacyDefault = true;
            }
        }
        assertTrue(foundPharmacyDefault, "consultations should appear in the catalogue listing");
    }

    // ----- helpers ----------------------------------------------------------

    private void signupPharmacy(String slug, String adminEmail) throws Exception {
        mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Pharm", "slug", slug, "verticalId", "pharmacy",
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

    private List<String> modulesFromJwt(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        JsonNode claims = objectMapper.readTree(payload);
        List<String> out = new java.util.ArrayList<>();
        claims.path("activeModules").forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
