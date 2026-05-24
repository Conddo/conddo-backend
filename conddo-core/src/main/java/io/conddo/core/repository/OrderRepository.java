package io.conddo.core.repository;

import io.conddo.core.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * RLS scopes every query to the current tenant, so {@code findAll(spec)} and the
 * counts below already see only this tenant's orders — no manual tenant filter.
 */
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /** Orders not in a terminal stage — the dashboard's "pending orders" KPI. */
    @Query("select count(o) from Order o where lower(o.stage) not in :terminalStages")
    long countPending(@Param("terminalStages") Collection<String> terminalStages);

    /** Pending orders past their due date — the "needs attention" delta. */
    @Query("select count(o) from Order o where o.dueDate < :today "
            + "and lower(o.stage) not in :terminalStages")
    long countOverdue(@Param("today") LocalDate today,
                      @Param("terminalStages") Collection<String> terminalStages);
}
