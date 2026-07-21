package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Invoice;
import io.conddo.core.domain.InvoiceLine;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.service.InvoiceService;
import io.conddo.core.service.InvoiceService.PublicView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PUBLIC read of a single invoice by its share token. Powers
 * {@code app.getconddo.com/i/{token}} — the customer-facing view.
 *
 * <p>No auth: the unguessable token IS the credential. Same shape as
 * {@code /public/managed-site} and {@code /public/book/{slug}}.
 * Response carries just enough tenant brand data to render the page
 * in the tenant's colours + logo without a second round-trip.
 */
@RestController
@RequestMapping("/api/v1/public/invoice")
public class PublicInvoiceController {

    private final InvoiceService invoiceService;
    private final TenantRepository tenantRepository;

    public PublicInvoiceController(InvoiceService invoiceService,
                                   TenantRepository tenantRepository) {
        this.invoiceService = invoiceService;
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/{token}")
    public ApiResponse<PublicInvoiceDto> get(@PathVariable String token) {
        PublicView view = invoiceService.resolveByPublicToken(token)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        Tenant tenant = tenantRepository.findById(view.invoice().getTenantId())
                .orElseThrow(() -> new NotFoundException("Business not found"));
        return ApiResponse.ok(PublicInvoiceDto.of(view, tenant));
    }

    // ----- wire shape ------------------------------------------------------

    public record PublicInvoiceDto(
            InvoiceDto invoice,
            BrandDto brand,
            BusinessDto business,
            List<LineDto> lines) {
        static PublicInvoiceDto of(PublicView v, Tenant tenant) {
            return new PublicInvoiceDto(
                    InvoiceDto.from(v.invoice()),
                    BrandDto.from(tenant),
                    BusinessDto.from(tenant),
                    v.lines().stream().map(LineDto::from).toList());
        }
    }

    public record InvoiceDto(UUID id, String invoiceNumber,
                              String customerName, String customerEmail,
                              String customerPhone, String customerAddress,
                              String currency, long subtotalKobo, long taxKobo,
                              long discountKobo, long totalKobo,
                              String status, LocalDate issueDate, LocalDate dueDate,
                              OffsetDateTime paidAt, String paidMethod,
                              String notes, String terms) {
        static InvoiceDto from(Invoice i) {
            return new InvoiceDto(i.getId(), i.getInvoiceNumber(),
                    i.getCustomerName(), i.getCustomerEmail(),
                    i.getCustomerPhone(), i.getCustomerAddress(),
                    i.getCurrency(), i.getSubtotalKobo(), i.getTaxKobo(),
                    i.getDiscountKobo(), i.getTotalKobo(),
                    i.getStatus(), i.getIssueDate(), i.getDueDate(),
                    i.getPaidAt(), i.getPaidMethod(),
                    i.getNotes(), i.getTerms());
        }
    }

    public record BrandDto(String logoUrl, String primaryColor, String secondaryColor) {
        static BrandDto from(Tenant t) {
            return new BrandDto(t.getLogoUrl(), t.getPrimaryColor(), t.getSecondaryColor());
        }
    }

    public record BusinessDto(String name, String slug, String contactEmail, String contactPhone) {
        static BusinessDto from(Tenant t) {
            return new BusinessDto(t.getName(), t.getSlug(),
                    t.getContactEmail(), t.getContactPhone());
        }
    }

    public record LineDto(String description, BigDecimal quantity,
                           long unitPriceKobo, BigDecimal taxRatePercent,
                           long lineTotalKobo) {
        static LineDto from(InvoiceLine l) {
            return new LineDto(l.getDescription(), l.getQuantity(),
                    l.getUnitPriceKobo(), l.getTaxRatePercent(),
                    l.getLineTotalKobo());
        }
    }
}
