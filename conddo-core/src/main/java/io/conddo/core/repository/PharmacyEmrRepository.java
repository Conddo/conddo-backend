package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyEmr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PharmacyEmrRepository extends JpaRepository<PharmacyEmr, UUID> {

    Optional<PharmacyEmr> findByCustomerId(UUID customerId);
}
