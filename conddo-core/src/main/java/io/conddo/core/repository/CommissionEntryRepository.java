package io.conddo.core.repository;

import io.conddo.core.domain.CommissionEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CommissionEntryRepository extends JpaRepository<CommissionEntry, UUID> {

    List<CommissionEntry> findByAgentIdOrderByAccruedAtDesc(UUID agentId);

    List<CommissionEntry> findByStatusOrderByAccruedAtDesc(String status);

    List<CommissionEntry> findByDealId(UUID dealId);

    @Query("select coalesce(sum(c.amount), 0) from CommissionEntry c " +
            "where c.status = 'accrued' and c.agentId = :agentId")
    BigDecimal totalOutstandingForAgent(UUID agentId);
}
