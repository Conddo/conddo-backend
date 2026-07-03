package io.conddo.core.repository;

import io.conddo.core.domain.DailyBrief;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DailyBriefRepository extends JpaRepository<DailyBrief, UUID> {

    Optional<DailyBrief> findByTenantIdAndBriefDate(UUID tenantId, LocalDate briefDate);
}
