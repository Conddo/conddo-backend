package io.conddo.studio.web;

import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.domain.AccountingEntry;
import io.conddo.studio.domain.FinancialMetrics;
import io.conddo.studio.domain.OperationalActivity;
import io.conddo.studio.domain.RoadmapItem;
import io.conddo.studio.domain.WeeklyMetricReview;
import io.conddo.studio.repository.AccountingEntryRepository;
import io.conddo.studio.repository.FinancialMetricsRepository;
import io.conddo.studio.repository.OperationalActivityRepository;
import io.conddo.studio.repository.RoadmapItemRepository;
import io.conddo.studio.repository.WeeklyMetricReviewRepository;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Internal operations controller for Conddo startup financial tracking and day-to-day activities.
 * Restricted to TEAM_LEAD and ADMIN roles for internal financial visibility.
 */
@RestController
@RequestMapping("/api/jobs/operations")
@PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
public class InternalOperationsController {

    private final FinancialMetricsRepository financialMetricsRepository;
    private final AccountingEntryRepository accountingEntryRepository;
    private final OperationalActivityRepository operationalActivityRepository;
    private final RoadmapItemRepository roadmapItemRepository;
    private final WeeklyMetricReviewRepository weeklyMetricReviewRepository;

    public InternalOperationsController(
            FinancialMetricsRepository financialMetricsRepository,
            AccountingEntryRepository accountingEntryRepository,
            OperationalActivityRepository operationalActivityRepository,
            RoadmapItemRepository roadmapItemRepository,
            WeeklyMetricReviewRepository weeklyMetricReviewRepository) {
        this.financialMetricsRepository = financialMetricsRepository;
        this.accountingEntryRepository = accountingEntryRepository;
        this.operationalActivityRepository = operationalActivityRepository;
        this.roadmapItemRepository = roadmapItemRepository;
        this.weeklyMetricReviewRepository = weeklyMetricReviewRepository;
    }

    // ==================== Financial Metrics Endpoints ====================

    @GetMapping("/financial/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFinancialSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        Optional<FinancialMetrics> latest = financialMetricsRepository.findLatest();
        latest.ifPresent(metrics -> {
            summary.put("cashBalance", metrics.getCashBalance());
            summary.put("netBurnRate", metrics.getNetBurnRate());
            summary.put("cashRunwayMonths", metrics.getCashRunwayMonths());
            summary.put("zeroCashDate", metrics.getZeroCashDate());
            summary.put("mrr", metrics.getMrr());
            summary.put("arr", metrics.getArr());
            summary.put("ltvToCacRatio", metrics.getLtvToCacRatio());
            summary.put("netRevenueRetention", metrics.getNetRevenueRetention());
            summary.put("totalCustomers", metrics.getTotalCustomers());
            summary.put("month", metrics.getMonth());
        });

        return ResponseEntity.ok(ApiResponse.ok(summary));
    }

    @GetMapping("/financial/history")
    public ResponseEntity<ApiResponse<List<FinancialMetrics>>> getFinancialHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        YearMonth start = startDate != null ? YearMonth.from(startDate) : YearMonth.now().minusMonths(12);
        YearMonth end = endDate != null ? YearMonth.from(endDate) : YearMonth.now();
        
        List<FinancialMetrics> metrics = financialMetricsRepository.findByMonthBetweenOrderByMonthAsc(start, end);
        return ResponseEntity.ok(ApiResponse.ok(metrics));
    }

    @PostMapping("/financial/metrics")
    public ResponseEntity<ApiResponse<FinancialMetrics>> createFinancialMetrics(
            @Valid @RequestBody FinancialMetrics metrics) {
        FinancialMetrics saved = financialMetricsRepository.save(metrics);
        return ResponseEntity.ok(ApiResponse.ok(saved));
    }

    @PatchMapping("/financial/metrics/{id}")
    public ResponseEntity<ApiResponse<FinancialMetrics>> updateFinancialMetrics(
            @PathVariable Long id, @RequestBody FinancialMetrics updates) {
        return financialMetricsRepository.findById(id)
                .map(metrics -> {
                    // Update fields
                    if (updates.getCashBalance() != null) metrics.setCashBalance(updates.getCashBalance());
                    if (updates.getGrossBurnRate() != null) metrics.setGrossBurnRate(updates.getGrossBurnRate());
                    if (updates.getNetBurnRate() != null) metrics.setNetBurnRate(updates.getNetBurnRate());
                    if (updates.getCashRunwayMonths() != null) metrics.setCashRunwayMonths(updates.getCashRunwayMonths());
                    if (updates.getZeroCashDate() != null) metrics.setZeroCashDate(updates.getZeroCashDate());
                    if (updates.getMrr() != null) metrics.setMrr(updates.getMrr());
                    if (updates.getArr() != null) metrics.setArr(updates.getArr());
                    if (updates.getNewMrr() != null) metrics.setNewMrr(updates.getNewMrr());
                    if (updates.getChurnedMrr() != null) metrics.setChurnedMrr(updates.getChurnedMrr());
                    if (updates.getExpansionMrr() != null) metrics.setExpansionMrr(updates.getExpansionMrr());
                    if (updates.getTotalCustomers() != null) metrics.setTotalCustomers(updates.getTotalCustomers());
                    if (updates.getNewCustomers() != null) metrics.setNewCustomers(updates.getNewCustomers());
                    if (updates.getChurnedCustomers() != null) metrics.setChurnedCustomers(updates.getChurnedCustomers());
                    if (updates.getCac() != null) metrics.setCac(updates.getCac());
                    if (updates.getLtv() != null) metrics.setLtv(updates.getLtv());
                    if (updates.getLtvToCacRatio() != null) metrics.setLtvToCacRatio(updates.getLtvToCacRatio());
                    if (updates.getCacPaybackMonths() != null) metrics.setCacPaybackMonths(updates.getCacPaybackMonths());
                    if (updates.getNetRevenueRetention() != null) metrics.setNetRevenueRetention(updates.getNetRevenueRetention());
                    if (updates.getGrossRevenueRetention() != null) metrics.setGrossRevenueRetention(updates.getGrossRevenueRetention());
                    
                    return ResponseEntity.ok(ApiResponse.ok(financialMetricsRepository.save(metrics)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Accounting Endpoints ====================

    @GetMapping("/accounting/entries")
    public ResponseEntity<ApiResponse<List<AccountingEntry>>> getAccountingEntries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) AccountingEntry.Category category,
            @RequestParam(required = false) AccountingEntry.EntryType type) {
        
        List<AccountingEntry> entries;
        if (startDate != null && endDate != null) {
            entries = accountingEntryRepository.findByEntryDateBetweenOrderByEntryDateAsc(startDate, endDate);
        } else if (category != null) {
            entries = accountingEntryRepository.findByCategoryOrderByIdDesc(category);
        } else if (type != null) {
            entries = accountingEntryRepository.findByTypeOrderByIdDesc(type);
        } else {
            entries = accountingEntryRepository.findRecentEntries(LocalDate.now().minusMonths(3));
        }
        
        return ResponseEntity.ok(ApiResponse.ok(entries));
    }

    @PostMapping("/accounting/entries")
    public ResponseEntity<ApiResponse<AccountingEntry>> createAccountingEntry(
            @Valid @RequestBody AccountingEntry entry) {
        AccountingEntry saved = accountingEntryRepository.save(entry);
        return ResponseEntity.ok(ApiResponse.ok(saved));
    }

    @GetMapping("/accounting/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAccountingSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        LocalDate start = startDate != null ? startDate : LocalDate.now().minusMonths(1);
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        
        Map<String, Object> summary = new HashMap<>();
        
        BigDecimal totalRevenue = accountingEntryRepository.sumByTypeAndDateRange(
                AccountingEntry.EntryType.REVENUE, start, end);
        BigDecimal totalExpenses = accountingEntryRepository.sumByTypeAndDateRange(
                AccountingEntry.EntryType.EXPENSE, start, end);
        BigDecimal cashIn = accountingEntryRepository.sumByTypeAndDateRange(
                AccountingEntry.EntryType.CASH_IN, start, end);
        BigDecimal cashOut = accountingEntryRepository.sumByTypeAndDateRange(
                AccountingEntry.EntryType.CASH_OUT, start, end);
        
        summary.put("totalRevenue", totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
        summary.put("totalExpenses", totalExpenses != null ? totalExpenses : BigDecimal.ZERO);
        summary.put("cashIn", cashIn != null ? cashIn : BigDecimal.ZERO);
        summary.put("cashOut", cashOut != null ? cashOut : BigDecimal.ZERO);
        summary.put("netCashFlow", (cashIn != null ? cashIn : BigDecimal.ZERO)
                .subtract(cashOut != null ? cashOut : BigDecimal.ZERO));
        summary.put("period", Map.of("start", start, "end", end));
        
        return ResponseEntity.ok(ApiResponse.ok(summary));
    }

    // ==================== Operational Activities Endpoints ====================

    @GetMapping("/activities")
    public ResponseEntity<ApiResponse<List<OperationalActivity>>> getActivities(
            @RequestParam(required = false) OperationalActivity.ActivityStatus status,
            @RequestParam(required = false) OperationalActivity.ActivityCategory category) {
        
        List<OperationalActivity> activities;
        if (status != null) {
            activities = operationalActivityRepository.findByStatusOrderByIdDesc(status);
        } else if (category != null) {
            activities = operationalActivityRepository.findByCategoryOrderByIdDesc(category);
        } else {
            activities = operationalActivityRepository.findActiveActivities();
        }
        
        return ResponseEntity.ok(ApiResponse.ok(activities));
    }

    @GetMapping("/activities/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getActivitiesDashboard() {
        Map<String, Object> dashboard = new HashMap<>();
        
        List<OperationalActivity> active = operationalActivityRepository.findActiveActivities();
        long plannedCount = operationalActivityRepository.countByStatus(OperationalActivity.ActivityStatus.PLANNED);
        long inProgressCount = operationalActivityRepository.countByStatus(OperationalActivity.ActivityStatus.IN_PROGRESS);
        long blockedCount = operationalActivityRepository.countByStatus(OperationalActivity.ActivityStatus.BLOCKED);
        long completedCount = operationalActivityRepository.countByStatus(OperationalActivity.ActivityStatus.COMPLETED);
        
        List<OperationalActivity> recentlyCompleted = operationalActivityRepository.findRecentlyCompleted(
                LocalDate.now().minusWeeks(2));
        
        dashboard.put("activeActivities", active);
        dashboard.put("statusCounts", Map.of(
                "planned", plannedCount,
                "inProgress", inProgressCount,
                "blocked", blockedCount,
                "completed", completedCount
        ));
        dashboard.put("recentlyCompleted", recentlyCompleted);
        
        return ResponseEntity.ok(ApiResponse.ok(dashboard));
    }

    @PostMapping("/activities")
    public ResponseEntity<ApiResponse<OperationalActivity>> createActivity(
            @Valid @RequestBody OperationalActivity activity) {
        OperationalActivity saved = operationalActivityRepository.save(activity);
        return ResponseEntity.ok(ApiResponse.ok(saved));
    }

    @PatchMapping("/activities/{id}")
    public ResponseEntity<ApiResponse<OperationalActivity>> updateActivity(
            @PathVariable Long id, @RequestBody OperationalActivity updates) {
        return operationalActivityRepository.findById(id)
                .map(activity -> {
                    if (updates.getTitle() != null) activity.setTitle(updates.getTitle());
                    if (updates.getDescription() != null) activity.setDescription(updates.getDescription());
                    if (updates.getCategory() != null) activity.setCategory(updates.getCategory());
                    if (updates.getStatus() != null) activity.setStatus(updates.getStatus());
                    if (updates.getStartDate() != null) activity.setStartDate(updates.getStartDate());
                    if (updates.getTargetDate() != null) activity.setTargetDate(updates.getTargetDate());
                    if (updates.getPriority() != null) activity.setPriority(updates.getPriority());
                    if (updates.getAssignedTo() != null) activity.setAssignedTo(updates.getAssignedTo());
                    if (updates.getTags() != null) activity.setTags(updates.getTags());
                    if (updates.getProgressNotes() != null) activity.setProgressNotes(updates.getProgressNotes());
                    
                    return ResponseEntity.ok(ApiResponse.ok(operationalActivityRepository.save(activity)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/activities/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteActivity(@PathVariable Long id) {
        operationalActivityRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ==================== Roadmap Planner Endpoints ====================

    @GetMapping("/roadmap")
    public ResponseEntity<ApiResponse<List<RoadmapItem>>> getRoadmapItems(
            @RequestParam(required = false) RoadmapItem.Status status,
            @RequestParam(required = false) RoadmapItem.Category category,
            @RequestParam(required = false) RoadmapItem.Priority priority,
            @RequestParam(required = false) String quarter) {
        
        List<RoadmapItem> items;
        if (status != null) {
            items = roadmapItemRepository.findByStatusOrderByIdDesc(status);
        } else if (category != null) {
            items = roadmapItemRepository.findByCategoryOrderByIdDesc(category);
        } else if (priority != null) {
            items = roadmapItemRepository.findByPriorityOrderByIdDesc(priority);
        } else if (quarter != null) {
            items = roadmapItemRepository.findByQuarterOrderByIdDesc(quarter);
        } else {
            items = roadmapItemRepository.findActiveRoadmapItems();
        }
        
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    @GetMapping("/roadmap/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRoadmapDashboard() {
        Map<String, Object> dashboard = new HashMap<>();
        
        List<RoadmapItem> active = roadmapItemRepository.findActiveRoadmapItems();
        long plannedCount = roadmapItemRepository.countByStatus(RoadmapItem.Status.PLANNED);
        long inProgressCount = roadmapItemRepository.countByStatus(RoadmapItem.Status.IN_PROGRESS);
        long blockedCount = roadmapItemRepository.countByStatus(RoadmapItem.Status.BLOCKED);
        long completedCount = roadmapItemRepository.countByStatus(RoadmapItem.Status.COMPLETED);
        
        List<RoadmapItem> recentlyCompleted = roadmapItemRepository.findRecentlyCompleted(
                LocalDate.now().minusWeeks(4));
        
        dashboard.put("activeItems", active);
        dashboard.put("statusCounts", Map.of(
                "planned", plannedCount,
                "inProgress", inProgressCount,
                "blocked", blockedCount,
                "completed", completedCount
        ));
        dashboard.put("recentlyCompleted", recentlyCompleted);
        
        return ResponseEntity.ok(ApiResponse.ok(dashboard));
    }

    @PostMapping("/roadmap")
    public ResponseEntity<ApiResponse<RoadmapItem>> createRoadmapItem(
            @Valid @RequestBody RoadmapItem item) {
        RoadmapItem saved = roadmapItemRepository.save(item);
        return ResponseEntity.ok(ApiResponse.ok(saved));
    }

    @PatchMapping("/roadmap/{id}")
    public ResponseEntity<ApiResponse<RoadmapItem>> updateRoadmapItem(
            @PathVariable Long id, @RequestBody RoadmapItem updates) {
        return roadmapItemRepository.findById(id)
                .map(item -> {
                    if (updates.getTitle() != null) item.setTitle(updates.getTitle());
                    if (updates.getDescription() != null) item.setDescription(updates.getDescription());
                    if (updates.getCategory() != null) item.setCategory(updates.getCategory());
                    if (updates.getPriority() != null) item.setPriority(updates.getPriority());
                    if (updates.getStatus() != null) item.setStatus(updates.getStatus());
                    if (updates.getTargetDate() != null) item.setTargetDate(updates.getTargetDate());
                    if (updates.getStartDate() != null) item.setStartDate(updates.getStartDate());
                    if (updates.getAssignedTo() != null) item.setAssignedTo(updates.getAssignedTo());
                    if (updates.getQuarter() != null) item.setQuarter(updates.getQuarter());
                    if (updates.getEstimatedHours() != null) item.setEstimatedHours(updates.getEstimatedHours());
                    if (updates.getActualHours() != null) item.setActualHours(updates.getActualHours());
                    if (updates.getDependencies() != null) item.setDependencies(updates.getDependencies());
                    if (updates.getSuccessCriteria() != null) item.setSuccessCriteria(updates.getSuccessCriteria());
                    if (updates.getNotes() != null) item.setNotes(updates.getNotes());
                    
                    return ResponseEntity.ok(ApiResponse.ok(roadmapItemRepository.save(item)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/roadmap/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRoadmapItem(@PathVariable Long id) {
        roadmapItemRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ==================== Weekly Metric Reviews Endpoints ====================

    @GetMapping("/weekly-reviews")
    public ResponseEntity<ApiResponse<List<WeeklyMetricReview>>> getWeeklyReviews(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        LocalDate start = startDate != null ? startDate : LocalDate.now().minusMonths(3);
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        
        List<WeeklyMetricReview> reviews = weeklyMetricReviewRepository.findByWeekStartDateBetweenOrderByWeekStartDateDesc(start, end);
        return ResponseEntity.ok(ApiResponse.ok(reviews));
    }

    @GetMapping("/weekly-reviews/latest")
    public ResponseEntity<ApiResponse<WeeklyMetricReview>> getLatestWeeklyReview() {
        return weeklyMetricReviewRepository.findLatest()
                .map(review -> ResponseEntity.ok(ApiResponse.ok(review)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/weekly-reviews")
    public ResponseEntity<ApiResponse<WeeklyMetricReview>> createWeeklyReview(
            @Valid @RequestBody WeeklyMetricReview review) {
        WeeklyMetricReview saved = weeklyMetricReviewRepository.save(review);
        return ResponseEntity.ok(ApiResponse.ok(saved));
    }

    @PatchMapping("/weekly-reviews/{id}")
    public ResponseEntity<ApiResponse<WeeklyMetricReview>> updateWeeklyReview(
            @PathVariable Long id, @RequestBody WeeklyMetricReview updates) {
        return weeklyMetricReviewRepository.findById(id)
                .map(review -> {
                    if (updates.getWeekStartDate() != null) review.setWeekStartDate(updates.getWeekStartDate());
                    if (updates.getWeekEndDate() != null) review.setWeekEndDate(updates.getWeekEndDate());
                    if (updates.getCashBalance() != null) review.setCashBalance(updates.getCashBalance());
                    if (updates.getNetBurnRate() != null) review.setNetBurnRate(updates.getNetBurnRate());
                    if (updates.getCashRunwayMonths() != null) review.setCashRunwayMonths(updates.getCashRunwayMonths());
                    if (updates.getMrr() != null) review.setMrr(updates.getMrr());
                    if (updates.getArr() != null) review.setArr(updates.getArr());
                    if (updates.getTotalCustomers() != null) review.setTotalCustomers(updates.getTotalCustomers());
                    if (updates.getNewCustomersThisWeek() != null) review.setNewCustomersThisWeek(updates.getNewCustomersThisWeek());
                    if (updates.getChurnedCustomersThisWeek() != null) review.setChurnedCustomersThisWeek(updates.getChurnedCustomersThisWeek());
                    if (updates.getCac() != null) review.setCac(updates.getCac());
                    if (updates.getLtv() != null) review.setLtv(updates.getLtv());
                    if (updates.getLtvToCacRatio() != null) review.setLtvToCacRatio(updates.getLtvToCacRatio());
                    if (updates.getNetRevenueRetention() != null) review.setNetRevenueRetention(updates.getNetRevenueRetention());
                    if (updates.getActiveUsers() != null) review.setActiveUsers(updates.getActiveUsers());
                    if (updates.getDailyActiveUsers() != null) review.setDailyActiveUsers(updates.getDailyActiveUsers());
                    if (updates.getNewSignups() != null) review.setNewSignups(updates.getNewSignups());
                    if (updates.getSupportTickets() != null) review.setSupportTickets(updates.getSupportTickets());
                    if (updates.getChurnRate() != null) review.setChurnRate(updates.getChurnRate());
                    if (updates.getHighlights() != null) review.setHighlights(updates.getHighlights());
                    if (updates.getConcerns() != null) review.setConcerns(updates.getConcerns());
                    if (updates.getKeyLearnings() != null) review.setKeyLearnings(updates.getKeyLearnings());
                    if (updates.getActionItems() != null) review.setActionItems(updates.getActionItems());
                    if (updates.getBlockers() != null) review.setBlockers(updates.getBlockers());
                    if (updates.getReviewedBy() != null) review.setReviewedBy(updates.getReviewedBy());
                    
                    return ResponseEntity.ok(ApiResponse.ok(weeklyMetricReviewRepository.save(review)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/weekly-reviews/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteWeeklyReview(@PathVariable Long id) {
        weeklyMetricReviewRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
