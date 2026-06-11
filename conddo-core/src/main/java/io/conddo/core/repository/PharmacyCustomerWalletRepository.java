package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyCustomerWallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyCustomerWalletRepository extends JpaRepository<PharmacyCustomerWallet, UUID> {

    Optional<PharmacyCustomerWallet> findByCustomerId(UUID customerId);

    List<PharmacyCustomerWallet> findAllByOrderByBalanceDesc();
}
