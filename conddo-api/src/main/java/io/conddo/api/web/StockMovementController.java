package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.StockMovement;
import io.conddo.core.service.StockMovementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/stock-movements} — the mobile app's write endpoint
 * for stock adjustments / restocks / sale-driven movements. Delegates
 * to {@link StockMovementService#recordMovement} which owns the
 * before/after math + audit trail + real-time inventory event.
 *
 * <p>List endpoint at {@code GET /api/v1/stock-movements} is a passthrough
 * over the same service for the mobile stock-history screen — filterable
 * by product + type.
 */
@RestController
@RequestMapping("/api/v1/stock-movements")
public class StockMovementController {

    private final StockMovementService service;

    public StockMovementController(StockMovementService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<MovementView> create(@Valid @RequestBody CreateRequest req,
                                            @AuthenticationPrincipal Jwt jwt) {
        UUID actor = jwt == null ? null : UUID.fromString(jwt.getSubject());
        StockMovement.Type type = parseType(req.type());
        StockMovement saved = service.recordMovement(
                req.productId(), type, req.delta(),
                req.referenceId(), req.referenceKind(),
                req.note(), actor);
        return ApiResponse.ok(MovementView.of(saved));
    }

    @GetMapping
    public ApiResponse<List<MovementView>> list(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.list(productId, type, page, size)
                .getContent().stream().map(MovementView::of).toList());
    }

    private StockMovement.Type parseType(String raw) {
        if (raw == null || raw.isBlank()) return StockMovement.Type.ADJUSTMENT;
        try {
            return StockMovement.Type.valueOf(raw.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown movement type '" + raw + "' — use SALE_ONLINE, SALE_POS, RESTOCK, or ADJUSTMENT");
        }
    }

    // ----- wire shape ------------------------------------------------------

    public record CreateRequest(
            @NotNull UUID productId,
            /** Signed integer — negative for outbound (sale/spoilage),
             *  positive for inbound (restock/return). Zero is rejected
             *  downstream. */
            int delta,
            /** ADJUSTMENT (default) / RESTOCK / SALE_ONLINE / SALE_POS. */
            String type,
            /** Optional back-reference to what caused the movement —
             *  order id, receipt id, PO id, etc. */
            UUID referenceId,
            String referenceKind,
            String note) {}

    public record MovementView(
            UUID id,
            UUID productId,
            String type,
            int delta,
            int quantityBefore,
            int quantityAfter,
            UUID referenceId,
            String referenceKind,
            String note,
            UUID createdBy,
            OffsetDateTime createdAt) {
        static MovementView of(StockMovement m) {
            return new MovementView(
                    m.getId(), m.getProductId(), m.getMovementType(),
                    m.getQuantityChange(), m.getQuantityBefore(), m.getQuantityAfter(),
                    m.getReferenceId(), m.getReferenceKind(),
                    m.getNote(), m.getCreatedBy(), m.getCreatedAt());
        }
    }
}
