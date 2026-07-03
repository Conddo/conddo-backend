package io.conddo.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.ai.AnthropicGateway;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.DailyBrief;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.DailyBriefRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Daily Business Brief — Conddo's opinionated one-paragraph "what to pay
 * attention to today" summary that greets the owner on every dashboard open.
 *
 * <p><b>Trigger:</b> lazy — first dashboard open of the day generates it,
 * subsequent opens serve the cached row from {@code daily_briefs}. A brief
 * is regenerated when the cached one is older than {@link #FRESH_WINDOW}
 * (12h), so afternoon logins get an updated read.
 *
 * <p><b>Cost:</b> free to the tenant (platform overhead). We still audit the
 * OpenRouter call through the standard log surface for margin tracking.
 *
 * <p><b>Verified-email gate:</b> the endpoint filters unverified tenants
 * before we get here, so this service can assume the tenant is authorised.
 */
@Service
public class DailyBriefService {

    static final Duration FRESH_WINDOW = Duration.ofHours(12);
    private static final Logger log = LoggerFactory.getLogger(DailyBriefService.class);

    private final DailyBriefRepository briefs;
    private final DashboardService dashboardService;
    private final CustomerRepository customerRepository;
    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;
    private final AnthropicGateway llm;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DailyBriefService(DailyBriefRepository briefs,
                             DashboardService dashboardService,
                             CustomerRepository customerRepository,
                             TenantRepository tenantRepository,
                             TenantSession tenantSession,
                             AnthropicGateway llm,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.briefs = briefs;
        this.dashboardService = dashboardService;
        this.customerRepository = customerRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public DailyBrief todayFor(UUID tenantId) {
        tenantSession.bind();
        LocalDate today = LocalDate.now(clock);

        DailyBrief cached = briefs.findByTenantIdAndBriefDate(tenantId, today).orElse(null);
        if (cached != null && isFresh(cached)) {
            return cached;
        }
        if (cached != null) {
            briefs.delete(cached);
        }
        return generateAndSave(tenantId, today);
    }

    // ----- internals --------------------------------------------------------

    private boolean isFresh(DailyBrief brief) {
        return Duration.between(brief.getGeneratedAt(), OffsetDateTime.now(clock))
                .compareTo(FRESH_WINDOW) < 0;
    }

    private DailyBrief generateAndSave(UUID tenantId, LocalDate briefDate) {
        Map<String, Object> snapshot = gatherSnapshot(tenantId);
        Generated generated = callLlm(snapshot);
        DailyBrief brief = new DailyBrief(
                tenantId, briefDate, generated.headline, generated.body, snapshot);
        return briefs.save(brief);
    }

    /** Assemble the data payload the LLM will summarise. Everything here is
     *  RLS-scoped to the current tenant; the JSON is what gets embedded in
     *  the prompt (and cached alongside the generated brief for auditability). */
    private Map<String, Object> gatherSnapshot(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        DashboardService.Summary summary = dashboardService.summary();

        // Recent customers — up to 5, last 30 days. Names + first activity
        // date; kept short so the prompt stays lean.
        OffsetDateTime start = OffsetDateTime.now(clock).minusDays(30);
        OffsetDateTime end = OffsetDateTime.now(clock);
        List<Customer> recent = customerRepository.findByCreatedAtBetween(start, end);
        List<Map<String, Object>> customers = recent.stream()
                .limit(5)
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", c.getName());
                    row.put("added", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
                    return row;
                })
                .toList();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("businessName", tenant.getName());
        snapshot.put("vertical", tenant.getVerticalId());
        snapshot.put("yesterday", Map.of(
                "revenue", summary.revenueToday().value(),
                "pendingOrders", summary.pendingOrders().value(),
                "newCustomers", summary.newCustomers().value(),
                "lowStockItems", summary.lowStockItems().value()));
        snapshot.put("recentCustomers", customers);
        return snapshot;
    }

    /** Ask the LLM to write the brief. Returns headline + body strings; on
     *  any error we fall back to a rule-based summary so the widget still
     *  renders something useful. */
    private Generated callLlm(Map<String, Object> snapshot) {
        String prompt;
        try {
            prompt = buildPrompt(snapshot);
        } catch (RuntimeException ex) {
            log.warn("DailyBrief prompt build failed: {}", ex.getMessage());
            return fallback(snapshot);
        }
        try {
            String raw = llm.chatText(prompt);
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            String headline = root.path("headline").asText("").trim();
            String body = root.path("body").asText("").trim();
            if (headline.isBlank() || body.isBlank()) {
                return fallback(snapshot);
            }
            return new Generated(clampLength(headline, 200), clampLength(body, 2000));
        } catch (RuntimeException | java.io.IOException ex) {
            log.warn("DailyBrief LLM call failed, falling back to rule-based: {}", ex.getMessage());
            return fallback(snapshot);
        }
    }

    private String buildPrompt(Map<String, Object> snapshot) {
        try {
            String snapshotJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            return "You write a warm, concise morning briefing for a Nigerian small-business owner. "
                    + "Speak like a smart, calm colleague who just read their dashboard.\n\n"
                    + "Rules:\n"
                    + "- Headline: 4-8 words, present tense, human. Never generic ('Good morning!').\n"
                    + "- Body: 2-3 sentences. Highlight the ONE thing that most needs attention today, plus ONE opportunity.\n"
                    + "- Use naira symbol (₦) not NGN.\n"
                    + "- Do not mention Conddo, AI, or 'as an AI'. Never end with a question.\n"
                    + "- If numbers are all zero (new tenant), write an encouraging setup nudge instead.\n\n"
                    + "Data:\n" + snapshotJson + "\n\n"
                    + "Return JSON ONLY: {\"headline\":\"...\",\"body\":\"...\"}";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise brief snapshot", e);
        }
    }

    /** Rule-based fallback — plain-english interpretation of the numbers so
     *  the widget never renders empty. */
    private Generated fallback(Map<String, Object> snapshot) {
        String businessName = String.valueOf(snapshot.getOrDefault("businessName", "your business"));
        @SuppressWarnings("unchecked")
        Map<String, Object> yesterday = (Map<String, Object>) snapshot.getOrDefault("yesterday", Map.of());
        Object revenue = yesterday.getOrDefault("revenue", 0);
        Object pending = yesterday.getOrDefault("pendingOrders", 0);
        Object lowStock = yesterday.getOrDefault("lowStockItems", 0);

        String headline;
        String body;
        if (isZero(revenue) && isZero(pending) && isZero(lowStock)) {
            headline = "Let's get " + businessName + " set up";
            body = "Add your first customer and record your first sale to start seeing your numbers here every morning.";
        } else {
            headline = "Here's your morning check-in";
            body = "Yesterday brought in ₦" + revenue + ".";
            if (!isZero(pending)) body += " " + pending + " orders are still pending.";
            if (!isZero(lowStock)) body += " " + lowStock + " items are running low — worth restocking today.";
        }
        return new Generated(headline, body);
    }

    private static boolean isZero(Object v) {
        if (v == null) return true;
        return "0".equals(v.toString()) || "0.00".equals(v.toString()) || "0.0".equals(v.toString());
    }

    private static String clampLength(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String extractJsonObject(String raw) {
        if (raw == null) throw new IllegalStateException("empty response");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) throw new IllegalStateException("no JSON in response");
        return raw.substring(start, end + 1);
    }

    private record Generated(String headline, String body) {
    }
}
