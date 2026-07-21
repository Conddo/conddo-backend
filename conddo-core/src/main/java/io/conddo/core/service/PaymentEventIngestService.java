package io.conddo.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.PaymentEvent;
import io.conddo.core.repository.PaymentEventRepository;
import io.conddo.core.tenant.TenantScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * First stop for every incoming payment webhook. Responsibilities:
 *
 * <ol>
 *   <li>Persist the raw body verbatim before any business logic runs.
 *       If the process dies mid-processing, the event is on disk and
 *       replay is trivial.</li>
 *   <li>Deduplicate on {@code (provider, providerEventId)} — providers
 *       retry aggressively; we must not reprocess.</li>
 *   <li>Return the persisted event so callers can dispatch business
 *       logic in a follow-up step. Dispatch failures are recorded on
 *       the same row for the reprocessor.</li>
 * </ol>
 *
 * <p>Runs under {@code @TenantScoped(crossTenant = true)} because
 * webhook processing is not user-scoped — the event isn't associated
 * with any particular tenant session.
 */
@Service
public class PaymentEventIngestService {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventIngestService.class);

    private final PaymentEventRepository events;
    private final ObjectMapper objectMapper;

    public PaymentEventIngestService(PaymentEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * Store an incoming event, or return the existing row if we've
     * already seen it. Idempotent by {@code (provider, providerEventId)}.
     *
     * @return {@code Result.newlyStored} true on first sight, false on retry
     */
    @TenantScoped(crossTenant = true)
    public Result ingest(String provider, String rawBody, String rawHeaders) {
        String eventId;
        String eventType;
        try {
            JsonNode tree = objectMapper.readTree(rawBody);
            eventId = deriveEventId(provider, tree, rawBody);
            eventType = deriveEventType(provider, tree);
        } catch (IOException ex) {
            log.warn("payment webhook {}: body not parseable, storing under body-hash", provider);
            eventId = sha256(rawBody);
            eventType = "unparseable";
        }

        Optional<PaymentEvent> existing = events.findByProviderAndProviderEventId(provider, eventId);
        if (existing.isPresent()) {
            return new Result(existing.get(), false);
        }

        PaymentEvent ev = new PaymentEvent();
        ev.setProvider(provider);
        ev.setProviderEventId(eventId);
        ev.setEventType(eventType);
        ev.setRawBody(rawBody);
        ev.setRawHeaders(rawHeaders);
        PaymentEvent saved = events.save(ev);
        return new Result(saved, true);
    }

    /** Record a processing failure without losing the row. */
    @TenantScoped(crossTenant = true)
    public void markFailed(PaymentEvent ev, String error) {
        ev.markFailed(error);
        events.save(ev);
    }

    /** Record a successful dispatch. */
    @TenantScoped(crossTenant = true)
    public void markProcessed(PaymentEvent ev) {
        ev.markProcessed();
        events.save(ev);
    }

    private static String deriveEventId(String provider, JsonNode tree, String rawBody) {
        // Providers differ in where they put their event id. Try the
        // common paths; fall back to a hash of the body so a missing id
        // never blocks ingest.
        String[] candidates = switch (provider) {
            case "paystack"   -> new String[]{"id", "data.id", "data.reference"};
            case "importapay" -> new String[]{"event_id", "id", "reference", "data.reference"};
            case "routepay"   -> new String[]{"event_id", "id", "transaction_id", "data.transaction_id"};
            default           -> new String[]{"id", "event_id"};
        };
        for (String path : candidates) {
            String v = readPath(tree, path);
            if (v != null && !v.isBlank()) return provider + ":" + v;
        }
        return provider + ":body:" + sha256(rawBody);
    }

    private static String deriveEventType(String provider, JsonNode tree) {
        String[] candidates = switch (provider) {
            case "paystack"   -> new String[]{"event"};
            case "importapay" -> new String[]{"event", "event_type", "type"};
            case "routepay"   -> new String[]{"event", "event_type", "type"};
            default           -> new String[]{"event", "type"};
        };
        for (String path : candidates) {
            String v = readPath(tree, path);
            if (v != null && !v.isBlank()) return v;
        }
        return "unknown";
    }

    private static String readPath(JsonNode root, String dotted) {
        JsonNode node = root;
        for (String part : dotted.split("\\.")) {
            node = node.path(part);
        }
        if (node.isMissingNode() || node.isNull()) return null;
        return node.asText();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return Integer.toHexString(s.hashCode());
        }
    }

    public record Result(PaymentEvent event, boolean newlyStored) {}
}
