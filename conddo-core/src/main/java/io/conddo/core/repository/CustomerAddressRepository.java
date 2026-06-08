package io.conddo.core.repository;

import io.conddo.core.domain.CustomerAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, UUID> {

    /** Customer's saved addresses (RLS-scoped to the bound tenant). */
    List<CustomerAddress> findByCustomerIdOrderByDefaultAddressDescCreatedAtDesc(UUID customerId);

    /** True when the customer has at least one address. */
    boolean existsByCustomerId(UUID customerId);
}
