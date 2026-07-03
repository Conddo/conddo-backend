package io.conddo.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
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
 * OpenRouter gateway — routes {@link #chatText} through OpenRouter's
 * OpenAI-compatible {@code /chat/completions} endpoint. Model is
 * configurable so a single deploy can flip between DeepSeek V3 (cheap,
 * high-volume classification), Claude Sonnet (compliance-sensitive),
 * Gemini Flash (marketing copy), etc. via {@code conddo.openrouter.model}.
 *
 * <p>Wins as {@link Primary @Primary} when {@code CONDDO_OPENROUTER_API_KEY}
 * is set, transparently replacing {@link HttpAnthropicGateway} for text
 * calls. Vision ({@link #chatWithImage}) still requires a vision-capable
 * model — OpenRouter can route Gemini Vision if the deployed model
 * supports it, but the default (deepseek-chat) will error. The Pharmacy
 * AI Product Assistant should keep its own Anthropic wiring for the
 * vision path.
 *
 * <p>Implements the {@link AnthropicGateway} interface (rather than
 * introducing a new one) so no downstream service needs to change —
 * dependency injection swaps the implementation. Rename the interface
 * to {@code LlmGateway} in a later cleanup pass.
 */
@Component
@Primary
@ConditionalOnExpression("'${conddo.openrouter.api-key:}' != ''")
public class HttpOpenRouterGateway implements AnthropicGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpOpenRouterGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final String referer;
    private final String appTitle;

    public HttpOpenRouterGateway(
            @Value("${conddo.openrouter.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${conddo.openrouter.api-key:}") String apiKey,
            @Value("${conddo.openrouter.model:deepseek/deepseek-chat}") String model,
            @Value("${conddo.openrouter.max-tokens:1024}") int maxTokens,
            @Value("${conddo.openrouter.referer:https://getconddo.com}") String referer,
            @Value("${conddo.openrouter.app-title:Conddo}") String appTitle,
            RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.referer = referer;
        this.appTitle = appTitle;
        log.info("OpenRouterGateway active (model={}, maxTokens={})", model, maxTokens);
    }

    /**
     * Vision through OpenRouter — passes the image via OpenAI's
     * {@code image_url} content type. Only works when the deployed model
     * is vision-capable (e.g. {@code google/gemini-flash-1.5},
     * {@code anthropic/claude-3.5-sonnet}); the DeepSeek default will
     * 400 with "model does not support images". Keep Pharmacy on the
     * Anthropic gateway until we deliberately route vision here.
     */
    @Override
    public String chatWithImage(String imageUrl, String prompt) {
        Map<String, Object> imageBlock = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", imageUrl));
        Map<String, Object> textBlock = Map.of("type", "text", "text", prompt);
        return callChatCompletions(List.of(imageBlock, textBlock), true);
    }

    @Override
    public String chatText(String prompt) {
        // Plain string content works fine for OpenAI-style endpoints —
        // no need for the content-blocks array on a text-only call.
        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        return callChatCompletionsRaw(List.of(message), true);
    }

    private String callChatCompletions(List<Map<String, Object>> content, boolean jsonResponse) {
        Map<String, Object> message = Map.of("role", "user", "content", content);
        return callChatCompletionsRaw(List.of(message), jsonResponse);
    }

    private String callChatCompletionsRaw(List<Map<String, Object>> messages, boolean jsonResponse) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("messages", messages);
        if (jsonResponse) {
            // response_format=json_object forces the model to return valid JSON,
            // avoiding the "extract the first { ... }" defensive parsing on the
            // service side. Supported by DeepSeek + Claude on OpenRouter.
            body.put("response_format", Map.of("type", "json_object"));
        }
        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    // OpenRouter uses HTTP-Referer + X-Title to attribute traffic
                    // in the dashboard; not required but useful for cost tracking.
                    .header("HTTP-Referer", referer)
                    .header("X-Title", appTitle)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new AnthropicUnavailableException("OpenRouter returned an empty body");
            }
            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AnthropicUnavailableException("OpenRouter response missing choices");
            }
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) {
                throw new AnthropicUnavailableException("OpenRouter response had no content");
            }
            return content;
        } catch (AnthropicUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("OpenRouter call failed: {}", ex.toString());
            throw new AnthropicUnavailableException("OpenRouter call failed: " + ex.getMessage(), ex);
        }
    }
}
