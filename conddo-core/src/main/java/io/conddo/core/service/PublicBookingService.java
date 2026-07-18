package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Booking;
import io.conddo.core.domain.Tenant;
import io.conddo.core.events.BookingCreatedEvent;
import io.conddo.core.repository.BookingRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import io.conddo.core.events.DomainEventBus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The PUBLIC, unauthenticated client-facing self-book flow (§11.5). There is no
 * JWT, so the tenant is resolved from the link slug against the (non-RLS)
 * tenants table, then bound onto {@link TenantContext} so RLS scopes the
 * booking writes/reads exactly as for an authenticated request. A disabled link
 * resolves to 404. Self-bookings land as {@code pending} for the owner to confirm.
 */
@Service
public class PublicBookingService {

    private static final String CANCELLED = "cancelled";

    private final TenantRepository tenantRepository;
    private final BookingRepository bookingRepository;
    private final DomainEventBus events;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PublicBookingService(TenantRepository tenantRepository, BookingRepository bookingRepository,
                                DomainEventBus events,
                                TenantSession tenantSession, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.bookingRepository = bookingRepository;
        this.events = events;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    /** The business's public availability + already-booked slots over the next two weeks. */
    @Transactional(readOnly = true)
    public PublicAvailability availability(String slug) {
        Tenant tenant = resolve(slug);
        TenantContext.set(tenant.getId());
        tenantSession.bind();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Slot> booked = bookingRepository
                .findByStartsAtBetweenAndStatusNotOrderByStartsAt(now, now.plusDays(14), CANCELLED)
                .stream().map(b -> new Slot(b.getStartsAt(), b.getEndsAt())).toList();
        Map<String, Object> hours = tenant.getWorkingHours() != null
                ? tenant.getWorkingHours() : BookingService.defaultWorkingHours();
        return new PublicAvailability(
                tenant.getName(),
                tenant.getSlug(),
                tenant.getLogoUrl(),
                tenant.getPrimaryColor(),
                tenant.getSecondaryColor(),
                hours,
                tenant.getSlotDurationMinutes(),
                tenant.getBufferMinutes(),
                booked);
    }

    /** Creates a pending booking from the public page; the owner confirms later. */
    @Transactional
    public Booking book(String slug, String customerName, String phone, String email,
                        String service, OffsetDateTime start) {
        Tenant tenant = resolve(slug);
        TenantContext.set(tenant.getId());
        tenantSession.bind();
        OffsetDateTime end = start.plusMinutes(tenant.getSlotDurationMinutes());
        Booking booking = new Booking(tenant.getId(), null, customerName, service, start, end, "in_person", "pending");
        StringBuilder note = new StringBuilder("Self-booked via link.");
        if (phone != null && !phone.isBlank()) {
            note.append(" Phone: ").append(phone).append('.');
        }
        if (email != null && !email.isBlank()) {
            note.append(" Email: ").append(email).append('.');
        }
        booking.setNotes(note.toString());
        booking = bookingRepository.save(booking);

        // Fan-out to bell-feed + owner alert + customer confirmation via the
        // BookingNotificationListener. Owner is handled by the existing
        // listener; customer confirmation fires only when email is present.
        events.publish(new BookingCreatedEvent(
                tenant.getId(), booking.getId(), customerName, service, start, phone, email,
                BookingCreatedEvent.Source.PUBLIC_WEBSITE));
        return booking;
    }

    private Tenant resolve(String slug) {
        Tenant tenant = tenantRepository.findByBookingLinkSlug(slug)
                .or(() -> tenantRepository.findBySlug(slug))
                .orElseThrow(() -> new NotFoundException("Booking page not found"));
        if (!tenant.isBookingLinkEnabled()) {
            throw new NotFoundException("Booking page not found");
        }
        return tenant;
    }

    /** A booked time slot — exposed publicly without any customer details. */
    public record Slot(OffsetDateTime start, OffsetDateTime end) {
    }

    /** Public availability + tenant brand so the customer sees a page that
     *  looks like the tenant's site, not generic Conddo chrome. {@code slug}
     *  is the tenant's own slug (distinct from the booking link slug) —
     *  the FE uses it to build canonical URLs and analytics keys. */
    public record PublicAvailability(String business,
                                     String slug,
                                     String logoUrl,
                                     String primaryColor,
                                     String secondaryColor,
                                     Map<String, Object> workingHours,
                                     int slotDurationMinutes,
                                     int bufferMinutes,
                                     List<Slot> booked) {
    }
}
