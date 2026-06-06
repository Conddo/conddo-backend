package io.conddo.core.repository;

import io.conddo.core.domain.CreativeServiceOffering;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Not RLS-scoped — the catalog is global. */
public interface CreativeServiceOfferingRepository extends JpaRepository<CreativeServiceOffering, UUID> {

    List<CreativeServiceOffering> findByActiveTrueOrderByPriceKoboAsc();

    Optional<CreativeServiceOffering> findByCode(String code);
}
