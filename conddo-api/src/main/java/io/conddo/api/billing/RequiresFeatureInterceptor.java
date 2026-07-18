package io.conddo.api.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.SubscriptionPlan;
import io.conddo.core.repository.SubscriptionPlanRepository;
import io.conddo.core.service.BillingService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantContextMissingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves {@link RequiresFeature} on the matched controller method and
 * short-circuits with 403 {@code PLAN_UPGRADE_REQUIRED} when the tenant's
 * plan doesn't unlock the feature.
 *
 * <p>Response body matches the FE's {@code PlanGate} component (BILLING_TIERS_SPEC §5):
 * <pre>
 * {
 *   "error": "PLAN_UPGRADE_REQUIRED",
 *   "message": "Ad management is available on the Growth plan.",
 *   "upgrade_url": "https://app.conddo.io/settings/billing",
 *   "requiredPlan": "Growth",
 *   "requiredPlanPrice": 45000
 * }
 * </pre>
 */
@Component
public class RequiresFeatureInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequiresFeatureInterceptor.class);

    private final BillingService billingService;
    private final SubscriptionPlanRepository planRepository;
    private final ObjectMapper objectMapper;
    private final String appBaseUrl;
    private final boolean enabled;

    public RequiresFeatureInterceptor(BillingService billingService,
                                      SubscriptionPlanRepository planRepository,
                                      ObjectMapper objectMapper,
                                      @Value("${conddo.app.base-url:https://app.conddo.io}") String appBaseUrl,
                                      @Value("${conddo.billing.enforce-feature-gates:true}") boolean enabled) {
        this.billingService = billingService;
        this.planRepository = planRepository;
        this.objectMapper = objectMapper;
        this.appBaseUrl = appBaseUrl;
        this.enabled = enabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled) {
            return true;
        }
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresFeature annotation = method.getMethodAnnotation(RequiresFeature.class);
        if (annotation == null) {
            annotation = method.getBeanType().getAnnotation(RequiresFeature.class);
        }
        if (annotation == null) {
            return true;
        }

        UUID tenantId;
        try {
            tenantId = TenantContext.require();
        } catch (TenantContextMissingException ex) {
            // Auth chain hasn't bound a tenant — let the security filters handle that.
            return true;
        }

        if (billingService.hasFeature(tenantId, annotation.value())) {
            return true;
        }

        log.debug("Tenant {} blocked from {} — feature {} requires plan {}",
                tenantId, method.getMethod().getName(), annotation.value(), annotation.requiredPlan());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // Live price from subscription_plans (V67 seeds it in kobo) rather than
        // the annotation's stale hardcoded default — Pricing v2 renumbered every
        // tier and the annotation predates that change.
        final RequiresFeature ann = annotation; // effectively-final capture for the lambdas
        String planName = ann.requiredPlan();
        int priceNaira = planRepository.findByName(planName.toLowerCase())
                .map(p -> p.getMonthlyPrice() == null ? ann.requiredPlanPrice() : p.getMonthlyPrice() / 100)
                .orElse(ann.requiredPlanPrice());
        String planDisplay = planRepository.findByName(planName.toLowerCase())
                .map(io.conddo.core.domain.SubscriptionPlan::getDisplayName)
                .orElse(planName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "PLAN_UPGRADE_REQUIRED");
        body.put("message", planDisplay + " plan is required for "
                + humanise(annotation.value()) + ".");
        body.put("upgrade_url", appBaseUrl + "/settings/billing");
        body.put("requiredPlan", planDisplay);
        body.put("requiredPlanPrice", priceNaira);

        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }

    private static String humanise(String featureKey) {
        return featureKey == null ? "this feature" : featureKey.replace('_', ' ');
    }
}
