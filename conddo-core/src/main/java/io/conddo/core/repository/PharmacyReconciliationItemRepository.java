package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyReconciliationItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyReconciliationItemRepository extends JpaRepository<PharmacyReconciliationItem, UUID> {

    List<PharmacyReconciliationItem> findByReconciliationIdOrderByProductId(UUID reconciliationId);

    Optional<PharmacyReconciliationItem> findByReconciliationIdAndProductId(UUID reconciliationId, UUID productId);
}
