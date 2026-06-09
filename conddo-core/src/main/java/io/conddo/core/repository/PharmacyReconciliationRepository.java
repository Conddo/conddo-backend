package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyReconciliation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PharmacyReconciliationRepository extends JpaRepository<PharmacyReconciliation, UUID> {

    List<PharmacyReconciliation> findByStatusOrderByStartedAtDesc(String status);
}
