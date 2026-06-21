package io.conddo.studio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Weekly metric reviews for tracking startup performance over time.
 * Captures key metrics weekly with analysis and action items.
 */
@Entity
@Table(name = "weekly_metric_reviews", schema = "studio")
public class WeeklyMetricReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate weekStartDate;

    @Column(nullable = false)
    private LocalDate weekEndDate;

    // Financial Metrics Snapshot
    @Column(precision = 19, scale = 2)
    private BigDecimal cashBalance;

    @Column(precision = 19, scale = 2)
    private BigDecimal netBurnRate;

    @Column
    private Integer cashRunwayMonths;

    @Column(precision = 19, scale = 2)
    private BigDecimal mrr;

    @Column(precision = 19, scale = 2)
    private BigDecimal arr;

    @Column
    private Integer totalCustomers;

    @Column
    private Integer newCustomersThisWeek;

    @Column
    private Integer churnedCustomersThisWeek;

    // Unit Economics
    @Column(precision = 19, scale = 2)
    private BigDecimal cac;

    @Column(precision = 19, scale = 2)
    private BigDecimal ltv;

    @Column(precision = 10, scale = 2)
    private BigDecimal ltvToCacRatio;

    @Column(precision = 5, scale = 2)
    private BigDecimal netRevenueRetention;

    // Operational Metrics
    @Column
    private Integer activeUsers;

    @Column
    private Integer dailyActiveUsers;

    @Column
    private Integer newSignups;

    @Column
    private Integer supportTickets;

    @Column(precision = 5, scale = 2)
    private BigDecimal churnRate;

    // Analysis & Actions
    @Column(columnDefinition = "TEXT")
    private String highlights;

    @Column(columnDefinition = "TEXT")
    private String concerns;

    @Column(columnDefinition = "TEXT")
    private String keyLearnings;

    @Column(columnDefinition = "TEXT")
    private String actionItems;

    @Column(columnDefinition = "TEXT")
    private String blockers;

    @Column(length = 100)
    private String reviewedBy;

    @Column(nullable = false, updatable = false)
    private LocalDate createdAt;

    @Column(nullable = false)
    private LocalDate updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDate.now();
        updatedAt = LocalDate.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDate.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getWeekStartDate() { return weekStartDate; }
    public void setWeekStartDate(LocalDate weekStartDate) { this.weekStartDate = weekStartDate; }

    public LocalDate getWeekEndDate() { return weekEndDate; }
    public void setWeekEndDate(LocalDate weekEndDate) { this.weekEndDate = weekEndDate; }

    public BigDecimal getCashBalance() { return cashBalance; }
    public void setCashBalance(BigDecimal cashBalance) { this.cashBalance = cashBalance; }

    public BigDecimal getNetBurnRate() { return netBurnRate; }
    public void setNetBurnRate(BigDecimal netBurnRate) { this.netBurnRate = netBurnRate; }

    public Integer getCashRunwayMonths() { return cashRunwayMonths; }
    public void setCashRunwayMonths(Integer cashRunwayMonths) { this.cashRunwayMonths = cashRunwayMonths; }

    public BigDecimal getMrr() { return mrr; }
    public void setMrr(BigDecimal mrr) { this.mrr = mrr; }

    public BigDecimal getArr() { return arr; }
    public void setArr(BigDecimal arr) { this.arr = arr; }

    public Integer getTotalCustomers() { return totalCustomers; }
    public void setTotalCustomers(Integer totalCustomers) { this.totalCustomers = totalCustomers; }

    public Integer getNewCustomersThisWeek() { return newCustomersThisWeek; }
    public void setNewCustomersThisWeek(Integer newCustomersThisWeek) { this.newCustomersThisWeek = newCustomersThisWeek; }

    public Integer getChurnedCustomersThisWeek() { return churnedCustomersThisWeek; }
    public void setChurnedCustomersThisWeek(Integer churnedCustomersThisWeek) { this.churnedCustomersThisWeek = churnedCustomersThisWeek; }

    public BigDecimal getCac() { return cac; }
    public void setCac(BigDecimal cac) { this.cac = cac; }

    public BigDecimal getLtv() { return ltv; }
    public void setLtv(BigDecimal ltv) { this.ltv = ltv; }

    public BigDecimal getLtvToCacRatio() { return ltvToCacRatio; }
    public void setLtvToCacRatio(BigDecimal ltvToCacRatio) { this.ltvToCacRatio = ltvToCacRatio; }

    public BigDecimal getNetRevenueRetention() { return netRevenueRetention; }
    public void setNetRevenueRetention(BigDecimal netRevenueRetention) { this.netRevenueRetention = netRevenueRetention; }

    public Integer getActiveUsers() { return activeUsers; }
    public void setActiveUsers(Integer activeUsers) { this.activeUsers = activeUsers; }

    public Integer getDailyActiveUsers() { return dailyActiveUsers; }
    public void setDailyActiveUsers(Integer dailyActiveUsers) { this.dailyActiveUsers = dailyActiveUsers; }

    public Integer getNewSignups() { return newSignups; }
    public void setNewSignups(Integer newSignups) { this.newSignups = newSignups; }

    public Integer getSupportTickets() { return supportTickets; }
    public void setSupportTickets(Integer supportTickets) { this.supportTickets = supportTickets; }

    public BigDecimal getChurnRate() { return churnRate; }
    public void setChurnRate(BigDecimal churnRate) { this.churnRate = churnRate; }

    public String getHighlights() { return highlights; }
    public void setHighlights(String highlights) { this.highlights = highlights; }

    public String getConcerns() { return concerns; }
    public void setConcerns(String concerns) { this.concerns = concerns; }

    public String getKeyLearnings() { return keyLearnings; }
    public void setKeyLearnings(String keyLearnings) { this.keyLearnings = keyLearnings; }

    public String getActionItems() { return actionItems; }
    public void setActionItems(String actionItems) { this.actionItems = actionItems; }

    public String getBlockers() { return blockers; }
    public void setBlockers(String blockers) { this.blockers = blockers; }

    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }

    public LocalDate getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDate createdAt) { this.createdAt = createdAt; }

    public LocalDate getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDate updatedAt) { this.updatedAt = updatedAt; }
}
