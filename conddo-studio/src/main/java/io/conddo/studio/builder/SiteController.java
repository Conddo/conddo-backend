package io.conddo.studio.builder;

import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.web.dto.NewSitePageRequest;
import io.conddo.studio.web.dto.NewSiteSectionRequest;
import io.conddo.studio.web.dto.PatchSiteThemeRequest;
import io.conddo.studio.web.dto.PutSiteRequest;
import io.conddo.studio.web.dto.SiteResponse;
import io.conddo.studio.web.dto.UpdateSitePageRequest;
import io.conddo.studio.web.dto.UpdateSiteSectionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Studio Website Builder endpoints (Infrastructure §21.3). Lazy-create on first
 * PUT, optimistic-lock via {@code If-Match: <version>}, server-side validation
 * of section content. Auto-publish on submit is wired in {@code JobService.submit}.
 */
@RestController
@RequestMapping("/api/jobs/{jobId}/site")
public class SiteController {

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @GetMapping
    public ApiResponse<SiteResponse> get(@PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(SiteResponse.from(siteService.get(jobId,
                StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt))));
    }

    @PutMapping
    public ApiResponse<SiteResponse> putSite(@PathVariable UUID jobId,
                                             @Valid @RequestBody PutSiteRequest body,
                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                             @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(SiteResponse.from(siteService.putSite(jobId,
                StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt),
                parseIfMatch(ifMatch), body.theme(), body.meta(), body.toPageInputs())));
    }

    @PatchMapping("/theme")
    public ApiResponse<SiteResponse> patchTheme(@PathVariable UUID jobId,
                                                @Valid @RequestBody PatchSiteThemeRequest body,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(SiteResponse.from(siteService.patchTheme(jobId,
                StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt),
                parseIfMatch(ifMatch), body.theme())));
    }

    @PostMapping("/pages")
    public ResponseEntity<ApiResponse<SiteResponse>> addPage(@PathVariable UUID jobId,
                                                             @Valid @RequestBody NewSitePageRequest body,
                                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                             @AuthenticationPrincipal Jwt jwt) {
        siteService.addPage(jobId, StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt),
                parseIfMatch(ifMatch), body.slug(), body.title(),
                body.home() != null && body.home(), body.order());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(SiteResponse.from(
                siteService.get(jobId, StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt)))));
    }

    @PatchMapping("/pages/{pageId}")
    public ApiResponse<SiteResponse> patchPage(@PathVariable UUID jobId, @PathVariable UUID pageId,
                                               @Valid @RequestBody UpdateSitePageRequest body,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @AuthenticationPrincipal Jwt jwt) {
        siteService.patchPage(jobId, pageId, StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt),
                parseIfMatch(ifMatch), body.slug(), body.title(), body.order(), body.home());
        return ApiResponse.ok(SiteResponse.from(
                siteService.get(jobId, StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt))));
    }

    @DeleteMapping("/pages/{pageId}")
    public ResponseEntity<Void> deletePage(@PathVariable UUID jobId, @PathVariable UUID pageId,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                           @AuthenticationPrincipal Jwt jwt) {
        siteService.deletePage(jobId, pageId, StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt),
                parseIfMatch(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pages/{pageId}/sections")
    public ResponseEntity<ApiResponse<SiteResponse>> addSection(@PathVariable UUID jobId, @PathVariable UUID pageId,
                                                                @Valid @RequestBody NewSiteSectionRequest body,
                                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                @AuthenticationPrincipal Jwt jwt) {
        siteService.addSection(jobId, pageId, StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt),
                parseIfMatch(ifMatch), body.type(), body.content(), body.order());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(SiteResponse.from(
                siteService.get(jobId, StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt)))));
    }

    @PatchMapping("/pages/{pageId}/sections/{sectionId}")
    public ApiResponse<SiteResponse> patchSection(@PathVariable UUID jobId, @PathVariable UUID pageId,
                                                  @PathVariable UUID sectionId,
                                                  @Valid @RequestBody UpdateSiteSectionRequest body,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                  @AuthenticationPrincipal Jwt jwt) {
        siteService.patchSection(jobId, pageId, sectionId,
                StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt),
                parseIfMatch(ifMatch), body.content(), body.order());
        return ApiResponse.ok(SiteResponse.from(
                siteService.get(jobId, StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt))));
    }

    @DeleteMapping("/pages/{pageId}/sections/{sectionId}")
    public ResponseEntity<Void> deleteSection(@PathVariable UUID jobId, @PathVariable UUID pageId,
                                              @PathVariable UUID sectionId,
                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              @AuthenticationPrincipal Jwt jwt) {
        siteService.deleteSection(jobId, pageId, sectionId,
                StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt), parseIfMatch(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/publish")
    public ApiResponse<SiteResponse> publish(@PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(SiteResponse.from(siteService.publish(jobId,
                StudioPrincipal.staffId(jwt), StudioPrincipal.role(jwt))));
    }

    /** Strip optional ETag quotes and parse the integer version. Null/blank → null. */
    private static Integer parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.startsWith("W/\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(3, trimmed.length() - 1);
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("If-Match must be an integer version, got: " + header);
        }
    }
}
