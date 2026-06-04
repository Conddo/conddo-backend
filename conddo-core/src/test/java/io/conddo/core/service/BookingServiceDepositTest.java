package io.conddo.core.service;

import io.conddo.core.domain.Booking;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Tenant;
import io.conddo.core.payments.PaymentsGateway;
import io.conddo.core.repository.BookingRepository;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins MS-2's contract: initWithDeposit refuses without a room, refuses
 * overlapping slots, refuses without a customer email, persists a
 * PENDING_DEPOSIT booking, and asks the payments gateway for a checkout URL.
 * markDepositPaid flips PENDING to confirmed idempotently.
 */
class BookingServiceDepositTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID ROOM_A = UUID.randomUUID();
    private static final OffsetDateTime SAT_14 = OffsetDateTime.parse("2026-06-06T14:00:00Z");
    private static final OffsetDateTime SAT_18 = OffsetDateTime.parse("2026-06-06T18:00:00Z");

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final TenantSession tenantSession = mock(TenantSession.class);
    private final PaymentsGateway paymentsGateway = mock(PaymentsGateway.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC);

    private final BookingService service = new BookingService(bookingRepository, customerRepository,
            tenantRepository, tenantSession, paymentsGateway, clock);

    @BeforeEach
    void bindTenant() {
        TenantContext.set(TENANT_ID);
        Tenant tenant = new Tenant("Wave Studios", "wave-studios", "music-studio", "starter");
        setField(Tenant.class, tenant, "id", TENANT_ID);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            setField(Booking.class, b, "id", UUID.randomUUID());
            return b;
        });
    }

    @Test
    void initWithDepositCreatesPendingBookingAndAsksPaymentsForCheckoutUrl() {
        when(bookingRepository.findResourceConflicts(eq(ROOM_A), eq(SAT_14), eq(SAT_18)))
                .thenReturn(List.of());
        when(paymentsGateway.initBookingDeposit(eq(TENANT_ID), eq("wave-studios"),
                any(UUID.class), any(), eq("wizzy@artist.test"), eq("Wizzy Beats"),
                eq(50_000L), anyString(), eq("https://app.conddo.io/return")))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-wave-1234", "https://pay.routepay.test/abc", "PENDING")));

        BookingService.InitWithDepositResult result = service.initWithDeposit(
                null, "Wizzy Beats", "wizzy@artist.test",
                "Saturday night session — Studio A", ROOM_A, "RECORDING",
                SAT_14, SAT_18, new BigDecimal("100000"), 50_000L,
                "https://app.conddo.io/return", null);

        assertNotNull(result.booking().getId());
        assertEquals("pending", result.booking().getStatus());
        assertEquals("PENDING_DEPOSIT", result.booking().getDepositStatus());
        assertEquals(50_000L, result.booking().getDepositAmountKobo());
        assertEquals(ROOM_A, result.booking().getResourceId());
        assertNotNull(result.payment());
        assertEquals("https://pay.routepay.test/abc", result.payment().paymentUrl());
    }

    @Test
    void overlappingBookingOnSameRoomIs409() {
        Booking existing = new Booking(TENANT_ID, null, "Existing Customer",
                "Other session", SAT_14.plusHours(1), SAT_14.plusHours(3),
                "in_person", "confirmed");
        setField(Booking.class, existing, "id", UUID.randomUUID());
        when(bookingRepository.findResourceConflicts(eq(ROOM_A), eq(SAT_14), eq(SAT_18)))
                .thenReturn(List.of(existing));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.initWithDeposit(null, "Wizzy", "wizzy@artist.test",
                        "Session", ROOM_A, "RECORDING", SAT_14, SAT_18,
                        BigDecimal.TEN, 5_000L, null, null));
        assertEquals(true, ex.getMessage().contains("already booked"));
        verify(paymentsGateway, never()).initBookingDeposit(any(), any(), any(), any(),
                any(), any(), anyLong(), any(), any());
    }

    @Test
    void missingResourceIdIs400() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initWithDeposit(null, "X", "x@x.test", "s",
                        null, "RECORDING", SAT_14, SAT_18, BigDecimal.TEN, 5_000L, null, null));
    }

    @Test
    void endsBeforeStartsIs400() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initWithDeposit(null, "X", "x@x.test", "s",
                        ROOM_A, "RECORDING", SAT_18, SAT_14, BigDecimal.TEN, 5_000L, null, null));
    }

    @Test
    void zeroOrNegativeDepositIs400() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initWithDeposit(null, "X", "x@x.test", "s",
                        ROOM_A, "RECORDING", SAT_14, SAT_18, BigDecimal.TEN, 0L, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.initWithDeposit(null, "X", "x@x.test", "s",
                        ROOM_A, "RECORDING", SAT_14, SAT_18, BigDecimal.TEN, -1L, null, null));
    }

    @Test
    void missingCustomerEmailIs400() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initWithDeposit(null, "X", "  ", "s",
                        ROOM_A, "RECORDING", SAT_14, SAT_18, BigDecimal.TEN, 5_000L, null, null));
    }

    @Test
    void paymentsGatewayUnreachableLeavesBookingPendingDepositAndPaymentNull() {
        when(bookingRepository.findResourceConflicts(any(), any(), any())).thenReturn(List.of());
        when(paymentsGateway.initBookingDeposit(any(), any(), any(), any(), any(), any(),
                anyLong(), any(), any())).thenReturn(Optional.empty());

        BookingService.InitWithDepositResult result = service.initWithDeposit(
                null, "X", "x@x.test", "s", ROOM_A, "RECORDING",
                SAT_14, SAT_18, BigDecimal.TEN, 5_000L, null, null);

        assertEquals("PENDING_DEPOSIT", result.booking().getDepositStatus());
        assertNull(result.payment(),
                "payments unreachable returns null payment — FE shows retry UI but booking still exists");
    }

    @Test
    void markDepositPaidFlipsPendingToConfirmedIdempotently() {
        UUID bookingId = UUID.randomUUID();
        Booking b = new Booking(TENANT_ID, null, "X", "s", SAT_14, SAT_18, "in_person", "pending");
        setField(Booking.class, b, "id", bookingId);
        b.requestDeposit(5_000L);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(b));
        when(bookingRepository.save(b)).thenReturn(b);

        Booking after = service.markDepositPaid(bookingId);

        assertEquals("DEPOSIT_PAID", after.getDepositStatus());
        assertEquals("confirmed", after.getStatus());

        // Re-applying the same notify is a no-op (idempotent terminal).
        Booking after2 = service.markDepositPaid(bookingId);
        assertEquals("DEPOSIT_PAID", after2.getDepositStatus());
    }

    // ----- helpers ------------------------------------------------------------

    private static void setField(Class<?> type, Object target, String name, Object value) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
