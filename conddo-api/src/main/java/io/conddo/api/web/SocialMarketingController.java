package io.conddo.api.web;

import io.conddo.api.billing.RequiresFeature;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.SocialPost;
import io.conddo.core.domain.SocialPostTarget;
import io.conddo.core.domain.TenantSocialProfile;
import io.conddo.core.service.SocialMarketingService;
import io.conddo.core.service.SocialMarketingService.ConnectLinkResult;
import io.conddo.core.service.SocialMarketingService.PostView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-facing social marketing surface
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §2-3). Every endpoint is gated by the
 * {@code social_scheduler} plan feature — Launcher tenants get 403
 * {@code PLAN_UPGRADE_REQUIRED} the moment they try to connect a channel.
 */
@RestController
@RequestMapping("/api/v1/marketing/social")
@PreAuthorize("@staffAccess.canRead('marketing')")
@RequiresFeature("social_scheduler")
public class SocialMarketingController {

    private final SocialMarketingService service;

    public SocialMarketingController(SocialMarketingService service) {
        this.service = service;
    }

    // ----- connect lifecycle -------------------------------------------------

    @GetMapping("/accounts")
    public ApiResponse<AccountsResponse> accounts() {
        return ApiResponse.ok(service.currentProfile()
                .map(SocialMarketingController::toAccountsResponse)
                .orElse(new AccountsResponse(List.of(), null, null)));
    }

    @PostMapping("/connect-link")
    public ApiResponse<ConnectLinkResponse> connectLink() {
        ConnectLinkResult result = service.connectLink();
        return ApiResponse.ok(new ConnectLinkResponse(result.connectUrl()));
    }

    @PostMapping("/accounts/{provider}/disconnect")
    public ApiResponse<AccountsResponse> disconnect(@PathVariable String provider) {
        TenantSocialProfile profile = service.disconnect(provider);
        return ApiResponse.ok(toAccountsResponse(profile));
    }

    // ----- posts -------------------------------------------------------------

    @GetMapping("/posts")
    public ApiResponse<List<PostResponse>> listPosts(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        OffsetDateTime fromTs = parseTs(from);
        OffsetDateTime toTs = parseTs(to);
        return ApiResponse.ok(service.listPosts(fromTs, toTs).stream()
                .map(p -> toPostResponse(p, List.of()))
                .toList());
    }

    @GetMapping("/posts/{id}")
    public ApiResponse<PostResponse> getPost(@PathVariable UUID id) {
        PostView view = service.get(id);
        return ApiResponse.ok(toPostResponse(view.post(), view.targets()));
    }

    @PostMapping("/posts")
    public ResponseEntity<ApiResponse<PostResponse>> schedule(
            @Valid @RequestBody SchedulePostRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID authorId = UUID.fromString(jwt.getSubject());
        PostView view = service.schedule(authorId, request.caption(), request.media(),
                request.scheduledAt(), request.timezone(), request.platforms());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                toPostResponse(view.post(), view.targets())));
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<ApiResponse<PostResponse>> cancel(@PathVariable UUID id) {
        PostView view = service.cancel(id);
        return ResponseEntity.ok(ApiResponse.ok(toPostResponse(view.post(), view.targets())));
    }

    // ----- DTOs --------------------------------------------------------------

    public record SchedulePostRequest(
            @NotBlank String caption,
            List<Map<String, Object>> media,
            @NotNull OffsetDateTime scheduledAt,
            String timezone,
            @NotEmpty List<String> platforms) {
    }

    public record ConnectLinkResponse(String connectUrl) {
    }

    public record AccountsResponse(List<String> platforms, String profileTitle,
                                   OffsetDateTime lastSyncedAt) {
    }

    public record PostResponse(UUID id, String caption, List<Map<String, Object>> media,
                               OffsetDateTime scheduledAt, String timezone, String status,
                               String ayrsharePostId, List<TargetResponse> targets,
                               OffsetDateTime createdAt) {
    }

    public record TargetResponse(String provider, String status, String externalPostId,
                                 String errorMessage, OffsetDateTime publishedAt) {
    }

    // ----- helpers -----------------------------------------------------------

    private static AccountsResponse toAccountsResponse(TenantSocialProfile p) {
        return new AccountsResponse(
                p.getConnectedPlatforms(),
                p.getAyrshareProfileTitle(),
                p.getLastSyncedAt());
    }

    private static PostResponse toPostResponse(SocialPost p, List<SocialPostTarget> targets) {
        return new PostResponse(
                p.getId(), p.getCaption(), p.getMedia(), p.getScheduledAt(), p.getTimezone(),
                p.getStatus(), p.getAyrsharePostId(),
                targets.stream().map(t -> new TargetResponse(
                        t.getProvider(), t.getStatus(), t.getExternalPostId(),
                        t.getErrorMessage(), t.getPublishedAt())).toList(),
                p.getCreatedAt());
    }

    private static OffsetDateTime parseTs(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
