package io.conddo.core.repository;

import io.conddo.core.domain.CustomerCart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerCartRepository extends JpaRepository<CustomerCart, UUID> {

    Optional<CustomerCart> findByCustomerId(UUID customerId);
}
