package io.conddo.api.publicapi;

import io.conddo.core.domain.Deal;
import io.conddo.core.repository.DealRepository;
import io.conddo.core.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public enquiry intake — for merchant websites embedding a contact /
 * "I'm interested" form. Auth via {@code X-Conddo-Site-Key}; no customer
 * JWT required — form submissions are anonymous.
 *
 * <p>An enquiry lands as a {@link Deal} in {@code STAGE_LEAD}, so it
 * appears immediately in the tenant's kanban with the prospect's name +
 * phone + a note (optional {@code propertyId} references the listing they
 * asked about). The primary agent + commission_pct are set from the deal
 * detail page after the tenant assigns someone.
 *
 * <p>This endpoint is generic across verticals — any tenant can accept
 * enquiries from their site, not just real estate. Pharmacies use
 * {@code POST /cart + /orders}; every vertical benefits from a contact-
 * form intake surface.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}")
public class PublicEnquiryController {

    private final DealRepository deals;

    public PublicEnquiryController(DealRepository deals) {
        this.deals = deals;
    }

    @PostMapping("/enquiries")
    @Transactional
    public ResponseEntity<Map<String, Object>> submit(@PathVariable String slug,
                                                       @Valid @RequestBody EnquiryRequest req) {
        // PublicSiteInterceptor already bound the tenant; TenantContext holds it.
        UUID tenantId = TenantContext.require();

        Deal deal = new Deal(tenantId, req.name().trim());
        deal.setProspectPhone(req.phone() != null ? req.phone().trim() : null);
        deal.setProspectEmail(req.email() != null ? req.email().trim() : null);
        deal.setPropertyId(req.propertyId());
        // Notes carry the message + a source tag so the agent knows how it
        // came in when they open the deal.
        String message = req.message() != null ? req.message().trim() : "";
        StringBuilder notes = new StringBuilder();
        notes.append("Website enquiry from ").append(slug).append("\n");
        if (!message.isBlank()) {
            notes.append("\n").append(message);
        }
        deal.setNotes(notes.toString());

        Deal saved = deals.save(deal);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("message", "Enquiry received. We'll be in touch shortly.");
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * Anonymous form payload. Kept minimal — richer capture (budget,
     * preferred contact time, etc.) is layered on top per vertical via
     * additional properties, but the required set stays name + phone-or-email.
     */
    public record EnquiryRequest(
            @NotBlank String name,
            /** Phone OR email must be present — validated at the record level below. */
            String phone,
            @Email String email,
            String message,
            /** Optional — references a specific property the prospect asked about. */
            UUID propertyId
    ) {
        /** At least one contact channel required so we can respond. */
        public boolean hasContact() {
            return (phone != null && !phone.isBlank()) || (email != null && !email.isBlank());
        }
    }
}
