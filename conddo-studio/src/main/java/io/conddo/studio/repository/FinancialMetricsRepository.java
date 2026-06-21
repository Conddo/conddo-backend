package io.conddo.studio.repository;

import io.conddo.studio.domain.FinancialMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Repository
public interface FinancialMetricsRepository extends JpaRepository<FinancialMetrics, Long> {

    Optional<FinancialMetrics> findByMonth(YearMonth month);

    List<FinancialMetrics> findByMonthBetweenOrderByMonthAsc(YearMonth start, YearMonth end);

    @Query("SELECT fm FROM FinancialMetrics fm ORDER BY fm.month DESC")
    List<FinancialMetrics> findLatestMetrics();

    @Query("SELECT fm FROM FinancialMetrics fm ORDER BY fm.month DESC LIMIT 1")
    Optional<FinancialMetrics> findLatest();
}
