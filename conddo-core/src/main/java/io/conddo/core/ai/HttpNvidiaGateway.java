package io.conddo.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NVIDIA build.nvidia.com gateway — talks to NVIDIA's OpenAI-compatible
 * {@code /chat/completions} endpoint. Used primarily by the signup
 * classifier path (see {@link AiGatewayRouter}) because Nemotron models
 * are fast + free on the current NIM API tier.
 *
 * <p><b>Not {@code @Primary}.</b> The router bean picks NVIDIA per action
 * and falls back to {@link HttpOpenRouterGateway} for everything else.
 * Registers only when {@code CONDDO_NVIDIA_API_KEY} is set — deployments
 * without a key silently omit this bean and the router defaults to
 * OpenRouter across the board.
 *
 * <p>Deliberately does not support vision — NVIDIA's inference API does
 * not expose a stable multimodal contract we can lean on yet. Vision calls
 * throw {@link UnsupportedOperationException} so the caller can fall
 * through to a vision-capable gateway rather than silently returning
 * garbage text for an image prompt.
 */
@Component
@ConditionalOnExpression("'${conddo.nvidia.api-key:}' != ''")
public class HttpNvidiaGateway implements AnthropicGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpNvidiaGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    private final RestClient restClient;
    private final String apiKey;
    private final String defaultModel;
    private final int maxTokens;

    public HttpNvidiaGateway(
            @Value("${conddo.nvidia.base-url:https://integrate.api.nvidia.com/v1}") String baseUrl,
            @Value("${conddo.nvidia.api-key:}") String apiKey,
            @Value("${conddo.nvidia.model:nvidia/llama-3.1-nemotron-70b-instruct}") String defaultModel,
            @Value("${conddo.nvidia.max-tokens:1024}") int maxTokens,
            RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.maxTokens = maxTokens;
        log.info("NvidiaGateway active (defaultModel={}, maxTokens={})", defaultModel, maxTokens);
    }

    @Override
    public String chatWithImage(String imageUrl, String prompt) {
        throw new UnsupportedOperationException(
                "NVIDIA gateway does not support vision — route the call to "
                        + "HttpAnthropicGateway or a vision-capable OpenRouter model.");
    }

    @Override
    public String chatText(String prompt) {
        return chatText(prompt, null);
    }

    @Override
    public String chatText(String prompt, String modelOverride) {
        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        return callChatCompletions(List.of(message), modelOverride);
    }

    private String callChatCompletions(List<Map<String, Object>> messages, String modelOverride) {
        Map<String, Object> body = new LinkedHashMap<>();
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : defaultModel;
        body.put("model", effectiveModel);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        body.put("messages", messages);
        // NVIDIA's NIM endpoint honors OpenAI's response_format for the
        // Nemotron family, so the classifier still gets guaranteed JSON.
        body.put("response_format", Map.of("type", "json_object"));
        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new AnthropicUnavailableException("NVIDIA returned an empty body");
            }
            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AnthropicUnavailableException("NVIDIA response missing choices");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new AnthropicUnavailableException("NVIDIA response had no content");
            }
            return content;
        } catch (AnthropicUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("NVIDIA call failed: {}", ex.toString());
            throw new AnthropicUnavailableException("NVIDIA call failed: " + ex.getMessage(), ex);
        }
    }
}
