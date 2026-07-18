package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.BookableService;
import io.conddo.core.service.BookableServiceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped services CRUD. Sits under the Bookings module; same
 * plan gate applies via {@code @RequiresFeature("bookings")}. Read is
 * open to any staff that can read orders; write is owner-only because
 * price + duration changes affect the public quote a customer sees.
 */
@RestController
@RequestMapping("/api/v1/bookings/services")
@io.conddo.api.billing.RequiresFeature(value = "bookings", requiredPlan = "growth")
public class BookableServicesController {

    private static final String READ = "@staffAccess.canRead('orders')";
    private static final String WRITE = "@staffAccess.ownerOnly()";

    private final BookableServiceService service;

    public BookableServicesController(BookableServiceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<ServiceRow>> list() {
        return ApiResponse.ok(service.list().stream().map(ServiceRow::from).toList());
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<ServiceRow>> create(@Valid @RequestBody UpsertRequest body) {
        BookableService created = service.create(
                body.name(), body.description(),
                body.durationMinutes(), body.priceKobo() == null ? 0 : body.priceKobo(),
                body.active() == null ? true : body.active(),
                body.sortOrder() == null ? 0 : body.sortOrder());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ServiceRow.from(created)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<ServiceRow> update(@PathVariable UUID id, @RequestBody UpsertRequest body) {
        return ApiResponse.ok(ServiceRow.from(service.update(
                id, body.name(), body.description(),
                body.durationMinutes() == 0 ? null : body.durationMinutes(),
                body.priceKobo(), body.active(), body.sortOrder())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ----- wire records ----------------------------------------------------

    /** Wire shape for a service row. */
    public record ServiceRow(UUID id, String name, String description,
                              int durationMinutes, long priceKobo,
                              boolean active, int sortOrder,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        static ServiceRow from(BookableService s) {
            return new ServiceRow(s.getId(), s.getName(), s.getDescription(),
                    s.getDurationMinutes(), s.getPriceKobo(),
                    s.isActive(), s.getSortOrder(),
                    s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    /** Same body for create + patch. On patch, null fields skip the update. */
    public record UpsertRequest(
            @NotBlank String name,
            String description,
            @Positive int durationMinutes,
            @PositiveOrZero Long priceKobo,
            Boolean active,
            Integer sortOrder) {}
}
