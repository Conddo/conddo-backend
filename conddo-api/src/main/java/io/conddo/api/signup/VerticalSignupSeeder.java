package io.conddo.api.signup;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.service.CustomerService;
import io.conddo.core.service.InventoryService;
import io.conddo.core.service.OrderService;
import io.conddo.core.signup.TenantActivatedEvent;
import io.conddo.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Per-vertical sample-data seeder. On {@link TenantActivatedEvent}, drops a
 * small set of realistic Nigerian SME records (customers + products + an
 * order or two) into the new tenant's tables so the dashboard isn't an empty
 * wall on day one.
 *
 * <p>Vertical-aware — only fires for Fashion and Pharmacy at V1 (the two
 * verticals we want to feel polished by the weekend launch). Other verticals
 * still get the standard empty state; we extend this listener as each
 * vertical gets its own polish pass.
 *
 * <p>Gated behind {@code conddo.signup.seed-sample-data} so tests can disable
 * the seeder (existing tests assert empty customer / product lists right
 * after signup). Default {@code true} — in prod every new tenant gets seeded.
 *
 * <p>Same fail-safe contract as the other {@link TenantActivatedEvent}
 * listeners: caught exceptions never propagate, so signup doesn't fail if a
 * seed insert hiccups.
 */
@Component
public class VerticalSignupSeeder {

    private static final Logger log = LoggerFactory.getLogger(VerticalSignupSeeder.class);

    private final TenantRepository tenantRepository;
    private final CustomerService customerService;
    private final InventoryService inventoryService;
    private final OrderService orderService;
    private final boolean enabled;

    public VerticalSignupSeeder(TenantRepository tenantRepository,
                                CustomerService customerService,
                                InventoryService inventoryService,
                                OrderService orderService,
                                @Value("${conddo.signup.seed-sample-data:true}") boolean enabled) {
        this.tenantRepository = tenantRepository;
        this.customerService = customerService;
        this.inventoryService = inventoryService;
        this.orderService = orderService;
        this.enabled = enabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTenantActivated_seedSampleData(TenantActivatedEvent event) {
        if (!enabled) {
            return;
        }
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));
            String vertical = tenant.getVerticalId() == null ? "" : tenant.getVerticalId().toLowerCase();

            // Bind tenant context so RLS lets the inserts through.
            TenantContext.set(tenant.getId());

            switch (vertical) {
                case "fashion":
                    seedFashion();
                    break;
                case "pharmacy":
                    seedPharmacy();
                    break;
                default:
                    // Other verticals don't get sample data yet — they land on the
                    // designed empty states. Extend this switch when polishing
                    // a new vertical.
            }
        } catch (RuntimeException ex) {
            log.error("Vertical seed failed for tenant {}: {}", event.tenantId(), ex.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ----- Fashion (tailoring boutique) --------------------------------------

    private void seedFashion() {
        // Three sample customers with realistic Nigerian names — the dashboard
        // KPI tiles and the customer list both have something to render.
        Customer adaeze = customerService.create("Adaeze Okeke", "adaeze@example.ng",
                "+2348012345601",
                "Regular client — prefers Ankara prints. Wedding party order in June.");
        customerService.create("Kemi Adeyemi", "kemi@example.ng", "+2348012345602",
                "Bridal client — booked for full bridal-party fittings.");
        customerService.create("Nneka Eze", "nneka@example.ng", "+2348012345603",
                "Occasional client — bespoke tailoring for events.");

        // Two orders in different stages so the Kanban board demos the pipeline.
        orderService.create(adaeze.getId(), adaeze.getFullName(), "Custom Ankara Dress",
                "Measurement Taken", new BigDecimal("45000"), LocalDate.now().plusDays(10),
                List.of(new OrderService.NewItem("Custom Ankara Dress (Adire fabric)", 1,
                        new BigDecimal("45000"))),
                java.util.Map.of("chest", "36", "waist", "30", "hips", "40", "length", "55"),
                "Customer prefers high-neck, A-line silhouette. Fitting scheduled in 5 days.");

        orderService.create(adaeze.getId(), adaeze.getFullName(), "Aso-Ebi (3 outfits)",
                "In Production", new BigDecimal("180000"), LocalDate.now().plusDays(21),
                List.of(new OrderService.NewItem("Aso-Ebi gown set", 3, new BigDecimal("60000"))),
                java.util.Map.of("chest", "36", "waist", "30", "hips", "40"),
                "Bridesmaid set — coordinated with the bridal Aso-Ebi colours.");

        log.info("Seeded fashion sample data for tenant");
    }

    // ----- Pharmacy (community pharmacy) -------------------------------------

    private void seedPharmacy() {
        // Three customers — pharmacy customers are about repeat refills, so the
        // names + notes reflect the "trusted local pharmacist" relationship.
        Customer mr_okafor = customerService.create("Mr. Chinedu Okafor", "chinedu.okafor@example.ng",
                "+2348023456701", "Hypertension medication on monthly refill.");
        customerService.create("Mrs. Funmi Bello", "funmi.bello@example.ng",
                "+2348023456702", "Diabetic — picks up insulin and lancets weekly.");
        customerService.create("Tunde Adigun", "tunde.adigun@example.ng",
                "+2348023456703", "Family medical supplies — wife buys for the whole household.");

        // Five common over-the-counter / pharmacy products to populate inventory.
        inventoryService.create("Paracetamol 500mg (Strip of 10)", "PCM-500-10", null,
                new BigDecimal("250"), 80, 20, true);
        inventoryService.create("Amoxicillin 500mg (Course of 21)", "AMX-500-21", null,
                new BigDecimal("1500"), 30, 10, true);
        inventoryService.create("Vitamin C 1000mg (Bottle of 30)", "VITC-1000-30", null,
                new BigDecimal("2000"), 50, 15, true);
        inventoryService.create("Blood Pressure Monitor", "BPM-DIGITAL", null,
                new BigDecimal("18000"), 4, 2, true);
        inventoryService.create("Hand Sanitiser 500ml", "SAN-500", null,
                new BigDecimal("1200"), 25, 10, true);

        // One in-flight order.
        orderService.create(mr_okafor.getId(), mr_okafor.getFullName(),
                "Monthly hypertension refill", "Processing",
                new BigDecimal("8500"), LocalDate.now().plusDays(2),
                List.of(new OrderService.NewItem("Amlodipine 5mg (course of 30)", 1, new BigDecimal("8500"))),
                java.util.Map.of(),
                "Repeat prescription — verified with patient on phone.");

        log.info("Seeded pharmacy sample data for tenant");
    }
}
