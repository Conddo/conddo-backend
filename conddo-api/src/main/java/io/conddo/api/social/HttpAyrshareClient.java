package io.conddo.api.social;

import com.fasterxml.jackson.databind.JsonNode;
import io.conddo.core.social.AyrshareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Real Ayrshare HTTP adapter (SOCIAL_AND_CREATIVE_SERVICES_SPEC §1).
 * Authenticates with the master API key as {@code Authorization: Bearer ...};
 * every call sends the tenant's {@code profileKey} in the body so Ayrshare
 * routes to the right User Profile.
 *
 * <p>Enabled only when {@code conddo.social.ayrshare-base-url} +
 * {@code conddo.social.ayrshare-api-key} are both set — fail-safe pattern
 * matching {@link io.conddo.api.payments.HttpPaymentsGateway}. When
 * unconfigured the methods return empty / false so endpoints can surface
 * a clean 503 {@code SOCIAL_UNCONFIGURED} instead of a stack trace.
 */
@Component
public class HttpAyrshareClient implements AyrshareClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAyrshareClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpAyrshareClient(RestClient.Builder restClientBuilder,
                              @Value("${conddo.social.ayrshare-base-url:https://api.ayrshare.com}") String baseUrl,
                              @Value("${conddo.social.ayrshare-api-key:}") String apiKey) {
        this(restClientBuilder, baseUrl, apiKey, defaultTimeoutFactory());
    }

    /** Test-friendly constructor — pass {@code null} to keep the builder's request factory. */
    public HttpAyrshareClient(RestClient.Builder restClientBuilder, String baseUrl, String apiKey,
                              ClientHttpRequestFactory requestFactoryOverride) {
        this.enabled = baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank();
        if (enabled) {
            RestClient.Builder configured = restClientBuilder
                    .baseUrl(baseUrl.trim())
                    .defaultHeader("Authorization", "Bearer " + apiKey.trim());
            if (requestFactoryOverride != null) {
                configured = configured.requestFactory(requestFactoryOverride);
            }
            this.restClient = configured.build();
        } else {
            this.restClient = null;
        }
    }

    @Override
    public boolean isConfigured() {
        return enabled;
    }

    @Override
    public Optional<ProfileCreate> createProfile(String title) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            JsonNode response = restClient.post()
                    .uri("/api/profiles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("title", title == null ? "Conddo Tenant" : title))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return Optional.empty();
            }
            String profileKey = response.path("profileKey").asText(null);
            if (profileKey == null || profileKey.isBlank()) {
                log.warn("Ayrshare /api/profiles returned no profileKey: {}", response);
                return Optional.empty();
            }
            return Optional.of(new ProfileCreate(profileKey, response.path("title").asText(null)));
        } catch (RuntimeException ex) {
            log.error("Ayrshare createProfile failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> connectLink(String profileKey) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            JsonNode response = restClient.get()
                    .uri(uri -> uri.path("/api/profiles/connectLink")
                            .queryParam("profileKey", profileKey).build())
                    .retrieve()
                    .body(JsonNode.class);
            String url = response == null ? null : response.path("url").asText(null);
            return url == null || url.isBlank() ? Optional.empty() : Optional.of(url);
        } catch (RuntimeException ex) {
            log.error("Ayrshare connectLink failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<String>> listConnectedPlatforms(String profileKey) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            JsonNode response = restClient.get()
                    .uri(uri -> uri.path("/api/user")
                            .queryParam("profileKey", profileKey).build())
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return Optional.empty();
            }
            JsonNode active = response.path("activeSocialAccounts");
            List<String> platforms = new ArrayList<>();
            if (active.isArray()) {
                active.forEach(n -> platforms.add(n.asText()));
            }
            return Optional.of(platforms);
        } catch (RuntimeException ex) {
            log.error("Ayrshare listConnectedPlatforms failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean disconnect(String profileKey, String provider) {
        if (!enabled) {
            return false;
        }
        try {
            restClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/api/social/unlink")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("profileKey", profileKey, "platforms", List.of(provider)))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.error("Ayrshare disconnect({}, {}) failed: {}", profileKey, provider, ex.getMessage());
            return false;
        }
    }

    @Override
    public Optional<PostDispatch> publish(String profileKey, String caption, List<String> platforms,
                                          List<String> mediaUrls, OffsetDateTime scheduleDate) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("profileKey", profileKey);
            body.put("post", caption);
            body.put("platforms", platforms);
            if (mediaUrls != null && !mediaUrls.isEmpty()) {
                body.put("mediaUrls", mediaUrls);
            }
            if (scheduleDate != null) {
                body.put("scheduleDate", scheduleDate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
            JsonNode response = restClient.post()
                    .uri("/api/post")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return Optional.empty();
            }

            String ayrsharePostId = response.path("id").asText(null);
            Map<String, TargetResult> targets = new LinkedHashMap<>();

            JsonNode postIds = response.path("postIds");
            if (postIds.isArray()) {
                // Per-platform response — Ayrshare returns one entry per requested channel.
                for (JsonNode entry : postIds) {
                    String platform = entry.path("platform").asText(null);
                    if (platform == null) {
                        continue;
                    }
                    String status = entry.path("status").asText("pending");
                    String extId = entry.path("postUrl").asText(entry.path("id").asText(null));
                    String err = entry.path("error").asText(null);
                    targets.put(platform.toLowerCase(),
                            new TargetResult(normaliseStatus(status), extId, err));
                }
            }
            return Optional.of(new PostDispatch(ayrsharePostId, targets));
        } catch (RuntimeException ex) {
            log.error("Ayrshare publish failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean cancelScheduledPost(String profileKey, String ayrsharePostId) {
        if (!enabled || ayrsharePostId == null || ayrsharePostId.isBlank()) {
            return false;
        }
        try {
            restClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/api/post")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("profileKey", profileKey, "id", ayrsharePostId))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.error("Ayrshare cancel({}) failed: {}", ayrsharePostId, ex.getMessage());
            return false;
        }
    }

    private static String normaliseStatus(String raw) {
        if (raw == null) {
            return "pending";
        }
        String s = raw.toLowerCase();
        return switch (s) {
            case "success", "published", "posted" -> "published";
            case "error", "failed" -> "failed";
            default -> "pending";
        };
    }

    private static ClientHttpRequestFactory defaultTimeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }
}
