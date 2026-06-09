package io.conddo.core.repository;

import io.conddo.core.domain.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Stock movement audit log (Pharmacy Spec v2 §12A). RLS-scoped to the
 * tenant; reads are filtered by product, movement type, and date
 * window — the service composes a JPA Specification for these filters
 * because Postgres can't infer the type of null
 * {@code OffsetDateTime} placeholders in a single JPQL string.
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID>,
        JpaSpecificationExecutor<StockMovement> {
}
