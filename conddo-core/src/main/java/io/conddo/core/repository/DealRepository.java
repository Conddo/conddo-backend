package io.conddo.core.repository;

import io.conddo.core.domain.Deal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface DealRepository extends JpaRepository<Deal, UUID> {

    /** Kanban list — all deals for the tenant, ordered by stage then recency. */
    List<Deal> findAllByOrderByStageChangedAtDesc();

    /** Filter to a single stage — for the kanban column virtualization. */
    List<Deal> findByStageOrderByStageChangedAtDesc(String stage);

    /** Group by stage → count. Feeds the pipeline widget on the dashboard home. */
    @Query("select d.stage as stage, count(d) as count, coalesce(sum(d.dealValue), 0) as pipelineValue " +
            "from Deal d group by d.stage")
    List<StageCount> countByStage();

    interface StageCount {
        String getStage();
        long getCount();
        java.math.BigDecimal getPipelineValue();
    }
}
