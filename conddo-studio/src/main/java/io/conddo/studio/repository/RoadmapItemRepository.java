package io.conddo.studio.repository;

import io.conddo.studio.domain.RoadmapItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoadmapItemRepository extends JpaRepository<RoadmapItem, Long> {

    List<RoadmapItem> findByStatusOrderByIdDesc(RoadmapItem.Status status);

    List<RoadmapItem> findByCategoryOrderByIdDesc(RoadmapItem.Category category);

    List<RoadmapItem> findByPriorityOrderByIdDesc(RoadmapItem.Priority priority);

    List<RoadmapItem> findByQuarterOrderByIdDesc(String quarter);

    List<RoadmapItem> findByAssignedToOrderByIdDesc(String assignedTo);

    List<RoadmapItem> findByTargetDateBetweenOrderByTargetDateAsc(LocalDate start, LocalDate end);

    @Query("SELECT ri FROM RoadmapItem ri WHERE ri.status IN ('PLANNED', 'IN_PROGRESS', 'BLOCKED') ORDER BY ri.priority ASC, ri.targetDate ASC")
    List<RoadmapItem> findActiveRoadmapItems();

    @Query("SELECT COUNT(ri) FROM RoadmapItem ri WHERE ri.status = :status")
    long countByStatus(RoadmapItem.Status status);

    @Query("SELECT ri FROM RoadmapItem ri WHERE ri.targetDate >= :start AND ri.targetDate <= :end ORDER BY ri.targetDate ASC")
    List<RoadmapItem> findByTargetDateRange(LocalDate start, LocalDate end);

    @Query("SELECT ri FROM RoadmapItem ri WHERE ri.completedDate >= :start ORDER BY ri.completedDate DESC")
    List<RoadmapItem> findRecentlyCompleted(LocalDate start);
}
