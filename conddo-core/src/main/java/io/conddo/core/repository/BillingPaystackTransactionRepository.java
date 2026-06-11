package io.conddo.core.repository;

import io.conddo.core.domain.BillingPaystackTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BillingPaystackTransactionRepository
        extends JpaRepository<BillingPaystackTransaction, UUID> {

    Optional<BillingPaystackTransaction> findByReference(String reference);
}
