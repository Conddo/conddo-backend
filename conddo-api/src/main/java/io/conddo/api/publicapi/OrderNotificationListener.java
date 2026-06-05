package io.conddo.api.publicapi;

import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.events.OrderCreatedEvent;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.service.NotificationFeedService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Notifies the merchant when a customer places an order on their public
 * conddo.io website (merchant-readiness slice 2). Fans out to three channels:
 * the in-app bell feed (§11.12), the owner's email, and the owner's SMS.
 *
 * <p>Runs <b>after commit</b> in a fresh transaction — a rolled-back order
 * (e.g. STOCK_SHORTAGE) never notifies, and a flaky notification provider
 * never bubbles to the customer who just checked out. Filters on
 * {@link OrderCreatedEvent.Source#PUBLIC_WEBSITE} so dashboard-typed orders
 * (where the merchant is already on the screen) stay silent.
 */
@Component
public class OrderNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationListener.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final NotificationFeedService notificationFeedService;
    private final NotificationService notificationService;
    private final TenantSession tenantSession;

    public OrderNotificationListener(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     NotificationFeedService notificationFeedService,
                                     NotificationService notificationService,
                                     TenantSession tenantSession) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.notificationFeedService = notificationFeedService;
        this.notificationService = notificationService;
        this.tenantSession = tenantSession;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderCreated(OrderCreatedEvent event) {
        if (event.source() != OrderCreatedEvent.Source.PUBLIC_WEBSITE) {
            return;
        }
        try {
            // Listener runs on its own thread (@Async) — bind the tenant
            // context manually so the bell feed + owner lookup are
            // RLS-scoped, then clear after.
            TenantContext.set(event.tenantId());
            tenantSession.bind();

            Tenant tenant = tenantRepository.findById(event.tenantId()).orElse(null);
            if (tenant == null) {
                return;
            }
            Optional<User> owner = userRepository.findFirstByRoleOrderByCreatedAtAsc("TENANT_ADMIN");

            String title = "New order " + event.orderReference();
            String body = event.customerName() + " ordered ₦" + formatNgn(event.totalNgn())
                    + " on your conddo.io site.";
            notificationFeedService.create("ORDER", title, body,
                    owner.map(User::getId).orElse(null));

            // Email/phone fall back to the tenant's business contact when
            // the owner user has neither set on their own profile — many
            // merchants set the business phone in Settings but never put
            // their personal one on the user record.
            String email = firstNonBlank(
                    owner.map(User::getEmail).orElse(null),
                    tenant.getContactEmail());
            String phone = firstNonBlank(
                    owner.map(User::getPhone).orElse(null),
                    tenant.getContactPhone());
            notificationService.sendOrderAlert(
                    email, phone, tenant.getName(),
                    event.customerName(),
                    event.orderReference(),
                    formatNgn(event.totalNgn()));
        } catch (RuntimeException ex) {
            // The customer checkout has already responded — never let a
            // notification blip surface as a 5xx.
            log.error("Order notification failed for tenant {} order {}: {}",
                    event.tenantId(), event.orderId(), ex.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private static String formatNgn(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return NumberFormat.getNumberInstance(Locale.US).format(amount);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
