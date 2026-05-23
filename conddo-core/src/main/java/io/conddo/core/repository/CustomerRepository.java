package io.conddo.core.repository;

import io.conddo.core.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Note: there is intentionally no {@code findByTenantId(...)} here. RLS scopes
 * results to the current tenant automatically, so {@code findAll()} already
 * returns only this tenant's customers. Manual tenant filtering would be both
 * redundant and a place for bugs.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {
}
