package io.conddo.api.studio;

import com.fasterxml.jackson.databind.JsonNode;
import io.conddo.core.studio.StudioJobGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Signed HTTP hand-off to Conddo Studio's intake endpoint (SERVICE_TOPOLOGY.md §4).
 * Authenticates with a shared service token in {@code X-Studio-Service-Token} —
 * <b>not</b> a staff JWT (this is a service principal, not a person).
 *
 * <p>Enabled only when {@code studio.base-url} and {@code studio.service-token}
 * are both set; otherwise it no-ops (returns empty) so owner requests are recorded
 * {@code PENDING} for manual pickup. Never throws on a transport error — a Studio
 * outage must not fail the owner's request. The bus-based variant (ARCH §9) can
 * replace this without changing {@link StudioJobGateway}.
 */
@Component
public class HttpStudioJobGateway implements StudioJobGateway {

    static final String SERVICE_TOKEN_HEADER = "X-Studio-Service-Token";
    private static final String INTAKE_PATH = "/api/jobs/intake";
    private static final Logger log = LoggerFactory.getLogger(HttpStudioJobGateway.class);

    /**
     * Hard ceilings on the Studio HTTP call. The listener fires AFTER_COMMIT in
     * the signup's request thread (no @Async yet), so an unbounded wait here
     * would block the signup response. Studio's Render free-tier cold start can
     * take 30–60s; we accept that the first signup after a Studio idle period
     * may fall back to the manual-recovery path, rather than freezing the user
     * for that long.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final boolean enabled;

    public HttpStudioJobGateway(RestClient.Builder restClientBuilder,
                                @Value("${studio.base-url:}") String baseUrl,
                                @Value("${studio.service-token:}") String serviceToken) {
        this.enabled = !baseUrl.isBlank() && !serviceToken.isBlank();
        if (enabled) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
            factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
            this.restClient = restClientBuilder
                    .baseUrl(baseUrl.trim())
                    .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken.trim())
                    .requestFactory(factory)
                    .build();
        } else {
            this.restClient = null;
        }
    }

    @Override
    public Optional<StudioJobRef> createJob(UUID tenantId, String jobType, String title, Map<String, Object> brief) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("jobTypeId", jobType);
            body.put("title", title);
            body.put("brief", brief);

            JsonNode response = restClient.post()
                    .uri(INTAKE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode data = response == null ? null : response.path("data");
            String id = data == null ? null : data.path("id").asText(null);
            if (id == null || id.isBlank()) {
                log.warn("Studio intake returned no job id for tenant {}", tenantId);
                return Optional.empty();
            }
            return Optional.of(new StudioJobRef(
                    UUID.fromString(id), data.path("jobNumber").asText(null), data.path("status").asText(null)));
        } catch (RuntimeException ex) {
            log.error("Studio job hand-off failed (tenant {}, type {}): {}", tenantId, jobType, ex.getMessage());
            return Optional.empty();
        }
    }
}
