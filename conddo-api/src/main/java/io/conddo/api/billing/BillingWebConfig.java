package io.conddo.api.billing;

import io.conddo.api.publicapi.PublicSiteInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the two cross-cutting interceptors used by the
 * BILLING_TIERS / WEBSITE_INTEGRATION specs:
 * <ul>
 *   <li>{@link RequiresFeatureInterceptor} on {@code /api/v1/**} for plan
 *       gating — excluded from the public-site surface (which has its own
 *       module gating downstream and uses a different auth path).</li>
 *   <li>{@link PublicSiteInterceptor} on {@code /api/v1/public/**} for
 *       header-based site auth + rate limiting + tenant binding.</li>
 * </ul>
 */
@Configuration
public class BillingWebConfig implements WebMvcConfigurer {

    private final RequiresFeatureInterceptor requiresFeature;
    private final PublicSiteInterceptor publicSite;

    public BillingWebConfig(RequiresFeatureInterceptor requiresFeature,
                            PublicSiteInterceptor publicSite) {
        this.requiresFeature = requiresFeature;
        this.publicSite = publicSite;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requiresFeature)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/public/**");
        // Public-site auth is for the FE-facing per-tenant surface only —
        // the pre-existing /api/v1/public/tenant/** (subdomain resolver) and
        // /api/v1/public/book/** (self-book widget) have their own auth model
        // and predate the API-key flow.
        registry.addInterceptor(publicSite)
                .addPathPatterns("/api/v1/public/**")
                .excludePathPatterns(
                        "/api/v1/public/tenant/**",
                        "/api/v1/public/book/**",
                        // Managed website renderer — public HTML anyway, no API
                        // key needed. See PublicManagedSiteController.
                        "/api/v1/public/managed-site/**");
    }
}
