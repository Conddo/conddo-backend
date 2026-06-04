package io.conddo.studio.web.dto;

import io.conddo.studio.builder.SiteService;
import io.conddo.studio.domain.SitePage;
import io.conddo.studio.domain.SiteSection;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire shape for {@code GET /api/jobs/:id/site} (§21.3). Mirrors the spec's
 * nested-tree shape so the FE renders the builder in a single state slot.
 * Always wrapped in {@link io.conddo.studio.common.ApiResponse} like every
 * other Studio endpoint.
 */
public record SiteResponse(UUID id, UUID jobId, String jobNumber,
                           Map<String, Object> theme, Map<String, Object> meta,
                           String status, OffsetDateTime publishedAt, int version,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt,
                           List<PageResponse> pages) {

    public static SiteResponse from(SiteService.SiteView view) {
        return new SiteResponse(
                view.site().getId(),
                view.site().getJobId(),
                view.job().getJobNumber(),
                view.site().getTheme(),
                view.site().getMeta(),
                view.site().getStatus(),
                view.site().getPublishedAt(),
                view.site().getVersion(),
                view.site().getCreatedAt(),
                view.site().getUpdatedAt(),
                view.pages().stream().map(PageResponse::from).toList());
    }

    public record PageResponse(UUID id, String slug, String title, boolean home,
                               int order, List<SectionResponse> sections) {

        public static PageResponse from(SiteService.PageView pv) {
            SitePage p = pv.page();
            return new PageResponse(p.getId(), p.getSlug(), p.getTitle(), p.isHome(), p.getOrderIndex(),
                    pv.sections().stream().map(SectionResponse::from).toList());
        }
    }

    public record SectionResponse(UUID id, String type, Map<String, Object> content, int order) {

        public static SectionResponse from(SiteSection s) {
            return new SectionResponse(s.getId(), s.getSectionType(), s.getContent(), s.getOrderIndex());
        }
    }
}
