package io.conddo.studio.repository;

import io.conddo.studio.domain.WeeklyMetricReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WeeklyMetricReviewRepository extends JpaRepository<WeeklyMetricReview, Long> {

    Optional<WeeklyMetricReview> findByWeekStartDate(LocalDate weekStartDate);

    List<WeeklyMetricReview> findByWeekStartDateBetweenOrderByWeekStartDateDesc(LocalDate start, LocalDate end);

    @Query("SELECT wmr FROM WeeklyMetricReview wmr ORDER BY wmr.weekStartDate DESC")
    List<WeeklyMetricReview> findLatestReviews();

    @Query("SELECT wmr FROM WeeklyMetricReview wmr ORDER BY wmr.weekStartDate DESC LIMIT 1")
    Optional<WeeklyMetricReview> findLatest();

    @Query("SELECT wmr FROM WeeklyMetricReview wmr WHERE wmr.weekStartDate >= :start ORDER BY wmr.weekStartDate ASC")
    List<WeeklyMetricReview> findFromStartDate(LocalDate start);
}
