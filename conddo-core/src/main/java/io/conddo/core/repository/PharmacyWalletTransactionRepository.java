package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyWalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PharmacyWalletTransactionRepository extends JpaRepository<PharmacyWalletTransaction, UUID> {

    List<PharmacyWalletTransaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId);
}
