package io.conddo.studio.repository;

import io.conddo.studio.domain.OperationalActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface OperationalActivityRepository extends JpaRepository<OperationalActivity, Long> {

    List<OperationalActivity> findByStatusOrderByIdDesc(OperationalActivity.ActivityStatus status);

    List<OperationalActivity> findByCategoryOrderByIdDesc(OperationalActivity.ActivityCategory category);

    List<OperationalActivity> findByAssignedToOrderByIdDesc(String assignedTo);

    List<OperationalActivity> findByTargetDateBetweenOrderByTargetDateAsc(LocalDate start, LocalDate end);

    List<OperationalActivity> findByStatusInOrderByPriorityAsc(List<OperationalActivity.ActivityStatus> statuses);

    @Query("SELECT oa FROM OperationalActivity oa WHERE oa.status IN ('PLANNED', 'IN_PROGRESS', 'BLOCKED') ORDER BY oa.priority ASC, oa.targetDate ASC")
    List<OperationalActivity> findActiveActivities();

    @Query("SELECT oa FROM OperationalActivity oa WHERE oa.targetDate >= :start AND oa.targetDate <= :end ORDER BY oa.targetDate ASC")
    List<OperationalActivity> findByTargetDateRange(LocalDate start, LocalDate end);

    @Query("SELECT COUNT(oa) FROM OperationalActivity oa WHERE oa.status = :status")
    long countByStatus(OperationalActivity.ActivityStatus status);

    @Query("SELECT oa FROM OperationalActivity oa WHERE oa.completedDate >= :start ORDER BY oa.completedDate DESC")
    List<OperationalActivity> findRecentlyCompleted(LocalDate start);
}
