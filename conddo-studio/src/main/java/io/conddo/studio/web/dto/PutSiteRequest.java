package io.conddo.studio.web.dto;

import io.conddo.studio.builder.SiteService;

import java.util.List;
import java.util.Map;

/**
 * Full-replace site body for {@code PUT /api/jobs/:id/site} (§21.3). Used by
 * the builder's "save all" and the import flow. Every field optional — empty
 * {@code pages} produces an empty site (no home page); typical clients send a
 * full tree.
 */
public record PutSiteRequest(Map<String, Object> theme,
                             Map<String, Object> meta,
                             List<PageBody> pages) {

    public List<SiteService.PageInput> toPageInputs() {
        if (pages == null) {
            return List.of();
        }
        return pages.stream()
                .map(p -> new SiteService.PageInput(p.slug(), p.title(), p.home(),
                        p.sections() == null ? List.of()
                                : p.sections().stream()
                                .map(s -> new SiteService.SectionInput(s.type(), s.content()))
                                .toList()))
                .toList();
    }

    public record PageBody(String slug, String title, boolean home, List<SectionBody> sections) {
    }

    public record SectionBody(String type, Map<String, Object> content) {
    }
}
