package io.conddo.core.repository;

import io.conddo.core.domain.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    Optional<CreditTransaction> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Recent transactions for the dashboard breakdown widget. */
    List<CreditTransaction> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
