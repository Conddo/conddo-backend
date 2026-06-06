package io.conddo.core.repository;

import io.conddo.core.domain.BrandPackageOffering;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrandPackageOfferingRepository extends JpaRepository<BrandPackageOffering, UUID> {

    List<BrandPackageOffering> findByActiveTrueOrderByMonthlyPriceKoboAsc();

    Optional<BrandPackageOffering> findByCode(String code);
}
