package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.BookableService;
import io.conddo.core.domain.Booking;
import io.conddo.core.domain.Tenant;
import io.conddo.core.events.BookingCreatedEvent;
import io.conddo.core.events.DomainEventBus;
import io.conddo.core.repository.BookableServiceRepository;
import io.conddo.core.repository.BookingRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final DayOfWeek[] WEEK_ORDER = DayOfWeek.values();

    private final TenantRepository tenantRepository;
    private final BookingRepository bookingRepository;
    private final BookableServiceRepository serviceRepository;
    private final DomainEventBus events;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PublicBookingService(TenantRepository tenantRepository, BookingRepository bookingRepository,
                                BookableServiceRepository serviceRepository,
                                DomainEventBus events,
                                TenantSession tenantSession, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.bookingRepository = bookingRepository;
        this.serviceRepository = serviceRepository;
        this.events = events;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    /** The business's public availability + already-booked slots + services menu. */
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
        List<PublicService> services = serviceRepository
                .findActiveByTenantIdForPublic(tenant.getId())
                .stream().map(PublicService::from).toList();
        return new PublicAvailability(
                tenant.getName(),
                tenant.getSlug(),
                tenant.getLogoUrl(),
                tenant.getPrimaryColor(),
                tenant.getSecondaryColor(),
                hours,
                tenant.getSlotDurationMinutes(),
                tenant.getBufferMinutes(),
                booked,
                services);
    }

    /**
     * Generate open slots for the next {@code days} days for {@code serviceId}
     * (or the tenant's default slot length when serviceId is null). Skips
     * closed days, past times on today, and any overlap with an existing
     * booking. Returns a chronologically sorted list.
     */
    @Transactional(readOnly = true)
    public List<OffsetDateTime> slots(String slug, UUID serviceId, int days) {
        int capped = Math.max(1, Math.min(days, 30));
        Tenant tenant = resolve(slug);
        TenantContext.set(tenant.getId());
        tenantSession.bind();

        int duration = tenant.getSlotDurationMinutes();
        if (serviceId != null) {
            BookableService svc = serviceRepository.findById(serviceId)
                    .orElseThrow(() -> new NotFoundException("Service not found"));
            duration = svc.getDurationMinutes();
        }
        int buffer = Math.max(0, tenant.getBufferMinutes());

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Slot> booked = bookingRepository
                .findByStartsAtBetweenAndStatusNotOrderByStartsAt(now, now.plusDays(capped), CANCELLED)
                .stream().map(b -> new Slot(b.getStartsAt(), b.getEndsAt())).toList();
        Map<String, Object> hours = tenant.getWorkingHours() != null
                ? tenant.getWorkingHours() : BookingService.defaultWorkingHours();

        List<OffsetDateTime> out = new ArrayList<>();
        LocalDate today = now.toLocalDate();
        for (int d = 0; d < capped; d++) {
            LocalDate date = today.plusDays(d);
            DayHours dh = readDayHours(hours, date.getDayOfWeek());
            if (dh == null || !dh.open) continue;
            LocalDateTime slotStart = LocalDateTime.of(date, dh.start);
            LocalDateTime dayEnd = LocalDateTime.of(date, dh.end);
            while (!slotStart.plusMinutes(duration).isAfter(dayEnd)) {
                OffsetDateTime slot = slotStart.atOffset(ZoneOffset.UTC);
                OffsetDateTime slotEnd = slot.plusMinutes(duration);
                if (slot.isAfter(now) && !overlapsAny(slot, slotEnd, booked)) {
                    out.add(slot);
                }
                slotStart = slotStart.plusMinutes(duration + buffer);
            }
        }
        return out;
    }

    /** Creates a pending booking from the public page; the owner confirms later. */
    @Transactional
    public Booking book(String slug, String customerName, String phone, String email,
                        String service, UUID serviceId, OffsetDateTime start) {
        Tenant tenant = resolve(slug);
        TenantContext.set(tenant.getId());
        tenantSession.bind();

        int duration = tenant.getSlotDurationMinutes();
        String resolvedService = service;
        long priceKobo = 0L;
        if (serviceId != null) {
            BookableService svc = serviceRepository.findById(serviceId)
                    .orElseThrow(() -> new NotFoundException("Service not found"));
            duration = svc.getDurationMinutes();
            resolvedService = svc.getName();
            priceKobo = svc.getPriceKobo();
        }
        OffsetDateTime end = start.plusMinutes(duration);

        Booking booking = new Booking(tenant.getId(), null, customerName, resolvedService,
                start, end, "in_person", "pending");
        StringBuilder note = new StringBuilder("Self-booked via link.");
        if (phone != null && !phone.isBlank()) {
            note.append(" Phone: ").append(phone).append('.');
        }
        if (email != null && !email.isBlank()) {
            note.append(" Email: ").append(email).append('.');
        }
        booking.setNotes(note.toString());
        if (priceKobo > 0) {
            booking.setAmount(new java.math.BigDecimal(priceKobo).movePointLeft(2));
        }
        booking = bookingRepository.save(booking);

        events.publish(new BookingCreatedEvent(
                tenant.getId(), booking.getId(), customerName, resolvedService, start, phone, email,
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

    // ----- slot helpers ---------------------------------------------------

    private static boolean overlapsAny(OffsetDateTime start, OffsetDateTime end, List<Slot> booked) {
        for (Slot b : booked) {
            if (start.isBefore(b.end()) && end.isAfter(b.start())) {
                return true;
            }
        }
        return false;
    }

    private static DayHours readDayHours(Map<String, Object> hours, DayOfWeek day) {
        String key = switch (day) {
            case MONDAY -> "mon";
            case TUESDAY -> "tue";
            case WEDNESDAY -> "wed";
            case THURSDAY -> "thu";
            case FRIDAY -> "fri";
            case SATURDAY -> "sat";
            case SUNDAY -> "sun";
        };
        Object raw = hours.get(key);
        if (!(raw instanceof Map<?, ?> m)) return null;
        boolean open = Boolean.TRUE.equals(m.get("open"));
        Object startRaw = m.get("start");
        Object endRaw = m.get("end");
        String s = startRaw == null ? "09:00" : String.valueOf(startRaw);
        String e = endRaw == null ? "17:00" : String.valueOf(endRaw);
        try {
            return new DayHours(open, LocalTime.parse(s), LocalTime.parse(e));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private record DayHours(boolean open, LocalTime start, LocalTime end) {}

    /** A booked time slot — exposed publicly without any customer details. */
    public record Slot(OffsetDateTime start, OffsetDateTime end) {
    }

    /** Public shape of a bookable service (no timestamps, no internal fields). */
    public record PublicService(UUID id, String name, String description,
                                 int durationMinutes, long priceKobo) {
        static PublicService from(BookableService s) {
            return new PublicService(s.getId(), s.getName(), s.getDescription(),
                    s.getDurationMinutes(), s.getPriceKobo());
        }
    }

    /** Public availability + tenant brand + services menu so the customer sees
     *  a page that looks like the tenant's site and picks from a real menu. */
    public record PublicAvailability(String business,
                                     String slug,
                                     String logoUrl,
                                     String primaryColor,
                                     String secondaryColor,
                                     Map<String, Object> workingHours,
                                     int slotDurationMinutes,
                                     int bufferMinutes,
                                     List<Slot> booked,
                                     List<PublicService> services) {
    }
}
