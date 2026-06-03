package io.conddo.studio.repository;

import io.conddo.studio.domain.DesignStandard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DesignStandardRepository extends JpaRepository<DesignStandard, UUID> {

    /** Active standards matching a vertical (or its globals) for AI prompt grounding. */
    List<DesignStandard> findByKindAndActiveTrueAndVerticalIn(String kind, List<String> verticals);

    /** Admin list, filtered by kind. */
    List<DesignStandard> findByKindOrderByVerticalAscNameAsc(String kind);

    /** Full admin list, deterministic order. */
    List<DesignStandard> findAllByOrderByKindAscVerticalAscNameAsc();
}
