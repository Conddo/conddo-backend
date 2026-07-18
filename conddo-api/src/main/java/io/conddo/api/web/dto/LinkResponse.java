package io.conddo.api.web.dto;

import io.conddo.core.service.BookingService.Link;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shareable self-book link (§11.5): slug, on/off state, and the
 * public URL.
 *
 * <p>URL shape: {@code {app-base-url}/book/{link-slug}} — i.e. served
 * from the main app subdomain (e.g. {@code https://app.getconddo.com/book/xyz})
 * because Vercel owns that host, whereas wildcard {@code *.getconddo.com}
 * routes through the tenant-site Caddy on EC2 which has no /book route.
 *
 * <p>Customer-facing branding is applied by the FE from
 * {@code PublicAvailability.logoUrl/primaryColor} — the URL host is a
 * routing detail, not a branding surface.
 *
 * <p>Base URL is injected via {@code conddo.app.base-url}. The old
 * hardcoded {@code conddo.io} literal was the bug that surfaced this
 * refactor; a brief subdomain experiment broke customers because their
 * page loads landed on Caddy instead of Vercel.
 */
public record LinkResponse(String slug, boolean enabled, String url) {

    /** Spring bean that owns the base-URL injection. Static factories
     *  can't use {@code @Value}, so we route {@link Link} → {@link LinkResponse}
     *  through this builder instead of a plain {@code static from(...)}. */
    @Component
    public static class Builder {
        private final String appBaseUrl;

        public Builder(@Value("${conddo.app.base-url:https://app.getconddo.com}") String appBaseUrl) {
            // Trim any trailing slash so we don't emit "https://app.getconddo.com//book/xyz".
            String v = appBaseUrl.trim();
            while (v.endsWith("/")) {
                v = v.substring(0, v.length() - 1);
            }
            this.appBaseUrl = v;
        }

        public LinkResponse from(Link link) {
            String url = appBaseUrl + "/book/" + link.slug();
            return new LinkResponse(link.slug(), link.enabled(), url);
        }
    }
}
