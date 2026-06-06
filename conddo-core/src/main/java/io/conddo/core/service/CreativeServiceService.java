package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.CreativeServiceOffering;
import io.conddo.core.domain.CreativeServiceRequest;
import io.conddo.core.domain.SocialPost;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.payments.PaymentsGateway;
import io.conddo.core.repository.CreativeServiceOfferingRepository;
import io.conddo.core.repository.CreativeServiceRequestRepository;
import io.conddo.core.repository.SocialPostRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.studio.StudioJobGateway;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the creative-service request lifecycle
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §5):
 *
 * <ol>
 *   <li>{@link #createRequest} — tenant picks an offering and a brief, the
 *       row is created {@code pending_payment} with the catalog price frozen
 *       on the row. {@link PaymentsGateway} returns a hosted checkout URL
 *       and a reference we pin to the request for the webhook to find.</li>
 *   <li>{@link #handlePaymentPaid} — conddo-payments fires our internal
 *       endpoint on RoutePay success; the request flips to {@code queued}
 *       and a Studio job is created (offering.jobType drives the routing).</li>
 *   <li>{@link #handleDelivered} — Studio fires our internal endpoint on
 *       delivery; final media flows back onto the request and (if the
 *       request was attached to a social post) onto the post's media
 *       array for the tenant to approve.</li>
 * </ol>
 *
 * <p>The two webhook handlers run without a tenant context. They use the
 * {@code app.public_resolver} carve-out (V25 pattern) to look up the
 * cross-tenant row, then bind the resolved tenant for any writes.
 */
@Service
public class CreativeServiceService {

    private static final Logger log = LoggerFactory.getLogger(CreativeServiceService.class);

    private final CreativeServiceOfferingRepository offeringRepository;
    private final CreativeServiceRequestRepository requestRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final SocialPostRepository socialPostRepository;
    private final PaymentsGateway paymentsGateway;
    private final StudioJobGateway studioJobGateway;
    private final BrandPackageService brandPackageService;
    private final TenantSession tenantSession;
    private final Clock clock;
    private final String appBaseUrl;

    @PersistenceContext
    private EntityManager entityManager;

    public CreativeServiceService(CreativeServiceOfferingRepository offeringRepository,
                                  CreativeServiceRequestRepository requestRepository,
                                  TenantRepository tenantRepository,
                                  UserRepository userRepository,
                                  SocialPostRepository socialPostRepository,
                                  PaymentsGateway paymentsGateway,
                                  StudioJobGateway studioJobGateway,
                                  BrandPackageService brandPackageService,
                                  TenantSession tenantSession,
                                  Clock clock,
                                  @Value("${conddo.app.base-url:https://app.conddo.io}") String appBaseUrl) {
        this.offeringRepository = offeringRepository;
        this.requestRepository = requestRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.socialPostRepository = socialPostRepository;
        this.paymentsGateway = paymentsGateway;
        this.studioJobGateway = studioJobGateway;
        this.brandPackageService = brandPackageService;
        this.tenantSession = tenantSession;
        this.clock = clock;
        this.appBaseUrl = appBaseUrl;
    }

    // ----- catalog -----------------------------------------------------------

    /** Active catalog — visible to every authenticated tenant (no plan gate). */
    @Transactional(readOnly = true)
    public List<CreativeServiceOffering> catalog() {
        return offeringRepository.findByActiveTrueOrderByPriceKoboAsc();
    }

    // ----- tenant-facing requests --------------------------------------------

    /**
     * Spec §5 step 1+2: create the pending-payment row, freeze the catalog
     * price, request a checkout URL from {@link PaymentsGateway}. Returns
     * both the row + URL so the controller can hand the FE a single payload.
     */
    @Transactional
    public CreateResult createRequest(UUID userId, String offeringCode, String brief,
                                      List<UUID> attachedMedia, UUID socialPostId) {
        tenantSession.bind();
        if (offeringCode == null || offeringCode.isBlank()) {
            throw new IllegalArgumentException("offeringCode is required");
        }
        if (brief == null || brief.isBlank()) {
            throw new IllegalArgumentException("brief is required");
        }
        CreativeServiceOffering offering = offeringRepository.findByCode(offeringCode.trim())
                .filter(CreativeServiceOffering::isActive)
                .orElseThrow(() -> new NotFoundException("Unknown creative-service offering: " + offeringCode));

        // SOCIAL_AND_CREATIVE_SERVICES_SPEC §6: active brand-package subscribers
        // ride on their bundle for as long as quota lasts. checkAndConsume()
        // returns true on a successful consume (price_kobo=0, no payment),
        // false when there's no subscription or this code isn't bundled,
        // and throws QuotaExhaustedException when included but used up
        // (controller maps to 409 — caller decides between waiting and
        // paying per-job, no automatic fallback).
        boolean bundleConsumed = brandPackageService.checkAndConsume(offering.getCode());

        int priceKobo = bundleConsumed ? 0 : offering.getPriceKobo();
        CreativeServiceRequest request = new CreativeServiceRequest(
                TenantContext.require(), userId, offering.getId(), socialPostId,
                brief.trim(), attachedMedia, priceKobo);
        request = requestRepository.save(request);

        if (bundleConsumed) {
            // Bundle ride: skip the payment step entirely; the Studio hand-off
            // mirrors the post-paid path so the operations team has nothing
            // new to learn.
            request.markPaid(null);   // status: pending_payment → queued
            queueStudioJob(request, offering);
            request = requestRepository.save(request);
            return new CreateResult(request, offering, null);
        }

        // Per-job paid path: hand off to the payments gateway and return the
        // checkout URL. The conddo-payments webhook fires our internal
        // endpoint with the reference, where handlePaymentPaid picks it up.
        String checkoutUrl = initCheckout(request, offering)
                .orElseThrow(() -> new PaymentsUnavailableException(
                        "Payments service is unreachable — please retry in a moment."));
        return new CreateResult(request, offering, checkoutUrl);
    }

    @Transactional(readOnly = true)
    public List<RequestView> listRequests() {
        tenantSession.bind();
        List<RequestView> out = new ArrayList<>();
        for (CreativeServiceRequest r : requestRepository.findByOrderByCreatedAtDesc()) {
            out.add(view(r));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public RequestView getRequest(UUID id) {
        tenantSession.bind();
        return view(requireRequest(id));
    }

    // ----- internal: payment paid -------------------------------------------

    /**
     * Spec §5 step 4-5: conddo-payments calls our internal endpoint with the
     * reference once RoutePay confirms. Flips the request to {@code queued},
     * creates a Studio job, pins the job id/number to the row. Idempotent —
     * a duplicate webhook just no-ops on the already-queued row.
     */
    @Transactional
    public void handlePaymentPaid(String paymentReference) {
        if (paymentReference == null || paymentReference.isBlank()) {
            return;
        }
        crossTenantBypass();
        CreativeServiceRequest request = requestRepository.findByPaymentReference(paymentReference).orElse(null);
        if (request == null) {
            log.warn("Payments webhook referenced unknown reference {}", paymentReference);
            return;
        }
        if (!CreativeServiceRequest.STATUS_PENDING_PAYMENT.equals(request.getStatus())) {
            return;   // already advanced past pending — duplicate webhook
        }
        TenantContext.set(request.getTenantId());
        tenantSession.bind();

        request.markPaid(paymentReference);
        CreativeServiceOffering offering = offeringRepository.findById(request.getOfferingId())
                .orElse(null);
        if (offering != null) {
            queueStudioJob(request, offering);
        }
        requestRepository.save(request);
    }

    /**
     * Hand off to Studio — fire-and-forget at the gateway level (returns
     * empty when the service is dormant). A later reconciliation can re-run
     * this branch on rows queued without studio_job_id set. Used by both
     * the per-job paid path and the bundle-consumed path.
     */
    private void queueStudioJob(CreativeServiceRequest request, CreativeServiceOffering offering) {
        Map<String, Object> brief = buildStudioBrief(request, offering);
        String title = offering.getName() + " — " + tenantNameFor(request.getTenantId());
        studioJobGateway.createJob(request.getTenantId(), offering.getJobType(), title, brief)
                .ifPresent(ref -> request.markQueued(ref.jobId(), ref.jobNumber()));
    }

    // ----- internal: delivered ----------------------------------------------

    /**
     * Spec §5 step 6-7: Studio fires our internal endpoint when the job is
     * marked delivered. Final media lands on the row, and on the originating
     * social post if the request was attached to one (the tenant later
     * approves the swap from the FE).
     */
    @Transactional
    public void handleDelivered(UUID requestId, List<Map<String, Object>> deliveryMedia) {
        crossTenantBypass();
        CreativeServiceRequest request = requestRepository.findById(requestId).orElse(null);
        if (request == null) {
            log.warn("Delivered webhook for unknown creative-service request {}", requestId);
            return;
        }
        if (CreativeServiceRequest.STATUS_DELIVERED.equals(request.getStatus())) {
            return;
        }
        TenantContext.set(request.getTenantId());
        tenantSession.bind();

        request.markDelivered(deliveryMedia == null ? List.of() : deliveryMedia, OffsetDateTime.now(clock));
        requestRepository.save(request);
        attachDeliveryToSocialPost(request, deliveryMedia);
    }

    // ----- helpers ----------------------------------------------------------

    private Optional<String> initCheckout(CreativeServiceRequest request, CreativeServiceOffering offering) {
        Tenant tenant = tenantRepository.findById(request.getTenantId()).orElse(null);
        User user = userRepository.findById(request.getUserId()).orElse(null);
        String tenantSlug = tenant == null ? null : tenant.getSlug();
        String userEmail = user == null ? null : user.getEmail();
        String userName = user == null ? null : user.getFullName();
        String returnUrl = appBaseUrl + "/marketing/creative-services?request=" + request.getId();
        String description = "Conddo creative — " + offering.getName();

        Optional<PaymentsGateway.PaymentInitResult> init = paymentsGateway.initCreativeServiceCharge(
                request.getTenantId(), tenantSlug, request.getId(), request.getUserId(),
                userEmail, userName, offering.getPriceKobo(), description, returnUrl);
        if (init.isEmpty()) {
            return Optional.empty();
        }
        request.setPaymentReference(init.get().reference());
        return Optional.ofNullable(init.get().paymentUrl());
    }

    private Map<String, Object> buildStudioBrief(CreativeServiceRequest request,
                                                 CreativeServiceOffering offering) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("source", "creative-service-request");
        brief.put("creativeServiceRequestId", request.getId().toString());
        brief.put("offeringCode", offering.getCode());
        brief.put("offeringName", offering.getName());
        brief.put("brief", request.getBrief());
        brief.put("priceKobo", request.getPriceKobo());
        brief.put("turnaroundHours", offering.getTurnaroundHours());
        if (request.getAttachedMedia() != null && !request.getAttachedMedia().isEmpty()) {
            brief.put("attachedMediaIds", request.getAttachedMedia());
        }
        if (request.getSocialPostId() != null) {
            brief.put("socialPostId", request.getSocialPostId().toString());
        }
        return brief;
    }

    private void attachDeliveryToSocialPost(CreativeServiceRequest request,
                                            List<Map<String, Object>> deliveryMedia) {
        if (request.getSocialPostId() == null || deliveryMedia == null || deliveryMedia.isEmpty()) {
            return;
        }
        socialPostRepository.findById(request.getSocialPostId()).ifPresent(post -> {
            List<Map<String, Object>> existing = post.getMedia();
            List<Map<String, Object>> merged = new ArrayList<>();
            if (existing != null) {
                merged.addAll(existing);
            }
            merged.addAll(deliveryMedia);
            post.setMedia(merged);
            socialPostRepository.save(post);
        });
    }

    private String tenantNameFor(UUID tenantId) {
        return tenantRepository.findById(tenantId).map(Tenant::getName).orElse("Tenant");
    }

    private CreativeServiceRequest requireRequest(UUID id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Creative-service request not found"));
    }

    private RequestView view(CreativeServiceRequest r) {
        CreativeServiceOffering offering = offeringRepository.findById(r.getOfferingId()).orElse(null);
        return new RequestView(r, offering);
    }

    /** Sets {@code app.public_resolver=true} for webhook lookups across tenants. */
    private void crossTenantBypass() {
        entityManager.createNativeQuery("SELECT set_config('app.public_resolver', 'true', true)")
                .getSingleResult();
    }

    // ----- result records + exceptions --------------------------------------

    public record CreateResult(CreativeServiceRequest request, CreativeServiceOffering offering,
                               String checkoutUrl) {
    }

    public record RequestView(CreativeServiceRequest request, CreativeServiceOffering offering) {
    }

    public static class PaymentsUnavailableException extends RuntimeException {
        public PaymentsUnavailableException(String msg) {
            super(msg);
        }
    }
}
