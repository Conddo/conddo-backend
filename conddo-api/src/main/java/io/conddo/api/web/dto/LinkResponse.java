package io.conddo.api.web.dto;

import io.conddo.core.service.BookingService.Link;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shareable self-book link (§11.5): slug, on/off state, and the
 * public URL.
 *
 * <p>URL shape: {@code https://{tenant-slug}.{base-domain}/book/{link-slug}}.
 * The booking page lives on the tenant's own subdomain so the site
 * renderer applies the tenant's brand (logo + colours) automatically —
 * the customer never sees generic Conddo chrome.
 *
 * <p>Base domain is injected via {@code conddo.base-domain} so
 * dev / staging / prod all resolve to the correct host without redeploy.
 * The old hardcoded {@code conddo.io} literal was the bug that surfaced
 * this refactor.
 */
public record LinkResponse(String slug, boolean enabled, String url) {

    /** Spring bean that owns the base-domain injection. Static factories
     *  can't use {@code @Value}, so we route {@link Link} → {@link LinkResponse}
     *  through this builder instead of a plain {@code static from(...)}. */
    @Component
    public static class Builder {
        private final String baseDomain;

        public Builder(@Value("${conddo.base-domain:getconddo.com}") String baseDomain) {
            this.baseDomain = baseDomain.trim().toLowerCase();
        }

        public LinkResponse from(Link link) {
            String url = "https://" + link.tenantSlug() + "." + baseDomain
                    + "/book/" + link.slug();
            return new LinkResponse(link.slug(), link.enabled(), url);
        }
    }
}
