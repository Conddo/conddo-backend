package io.conddo.core.repository;

import io.conddo.core.domain.TenantPaymentAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantPaymentAccountRepository extends JpaRepository<TenantPaymentAccount, UUID> {

    /** Admin console — list accounts awaiting review, oldest submitted first. */
    List<TenantPaymentAccount> findByKycStatusOrderByKycSubmittedAtAsc(String kycStatus);
}
