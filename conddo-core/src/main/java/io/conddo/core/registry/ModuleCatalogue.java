package io.conddo.core.registry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Human-readable descriptions for the modules referenced in the
 * vertical YAMLs — used by the AI suggestion flow (Phase C) so
 * Anthropic can score relevance against business descriptions.
 *
 * <p>Adding a module = adding an entry here. The vertical YAMLs
 * stay the source of truth for which modules SHIP per vertical;
 * this is just the prose surface.
 */
public final class ModuleCatalogue {

    private static final Map<String, String> DESCRIPTIONS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("website", "Public-facing business website with sections, hours, services");
        d.put("crm", "Customer relationship management — contacts, history, notes");
        d.put("crm.pharmacy", "Pharmacy-specific CRM with patient profiles + medication history");
        d.put("inventory", "Stock and product management");
        d.put("inventory.pharmacy", "Pharmacy stock management with batch + expiry + reorder");
        d.put("inventory.retail", "Retail stock management");
        d.put("pos", "Point-of-sale terminal for in-store purchases");
        d.put("pos.pharmacy", "Pharmacy POS with prescription gating + cashback loyalty");
        d.put("orders", "Order management — incoming orders, pipeline, fulfillment");
        d.put("orders.fashion", "Fashion-specific orders — measurements, fabric, fittings");
        d.put("orders.logistics", "Logistics-specific orders — packages, routes, delivery");
        d.put("bookings", "Appointment booking — calendars, slots, deposits");
        d.put("fittings.fashion", "Fashion-specific fitting appointments");
        d.put("fabric.fashion", "Fabric inventory + sourcing for tailors");
        d.put("sessions.music-studio", "Music studio session pipeline — booth, engineer, deposit");
        d.put("prescriptions", "Prescription intake + dispensing tracking");
        d.put("consultations", "Practitioner consultations with notes + history");
        d.put("payments", "Online + offline payment processing");
        d.put("analytics", "Business analytics — revenue, orders, customers");
        d.put("analytics.pharmacy", "Pharmacy-specific analytics — top SKUs, refill rates, shrinkage");
        d.put("staff", "Staff management — roles, invites, permissions, activity");
        d.put("marketing.social", "Social media scheduling + publishing");
        d.put("marketing.email", "Email marketing campaigns");
        d.put("marketing.sms", "SMS marketing campaigns");
        d.put("marketing.leads", "Lead capture + nurturing");
        d.put("marketing.ads", "Paid advertising campaigns");
        d.put("document-vault", "Secure document storage + sharing");
        d.put("table-mgmt", "Restaurant table management + reservations");
        d.put("loyalty", "Loyalty / rewards program");
        d.put("projects", "Project tracking — milestones, deliverables");
        d.put("ecommerce", "Online storefront with checkout");
        d.put("tracking.advanced", "Advanced logistics tracking — live GPS, ETA, proof-of-delivery");
        d.put("music-school", "Music lesson scheduling for music schools");
        // Real Estate vertical modules
        d.put("properties", "Property listings — houses, land, commercial, with photos + location + status");
        d.put("viewings", "Property viewings — bookings tied to a specific property + agent");
        d.put("deals", "Deal pipeline — offer, deposit, documentation, close (with agent + commission)");
        d.put("contracts", "Legal document management — C of O, Deed of Assignment, Tenancy Agreement");
        d.put("commissions", "Agent commission tracking — % of deal, splits, payout on deposit received");
        d.put("rentals", "Rental lease management — periodic rent, arrears, remittance to owner");
        d.put("owners", "Property owner records for management companies");
        DESCRIPTIONS = Map.copyOf(d);
    }

    private ModuleCatalogue() {
    }

    /** Returns the full id → description map. Modules not in the map are still valid but have no prose. */
    public static Map<String, String> descriptions() {
        return DESCRIPTIONS;
    }

    public static String describe(String moduleId) {
        return DESCRIPTIONS.getOrDefault(moduleId, moduleId);
    }
}
