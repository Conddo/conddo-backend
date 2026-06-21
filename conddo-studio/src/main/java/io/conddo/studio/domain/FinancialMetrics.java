package io.conddo.studio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Internal financial metrics for Conddo startup operations.
 * Tracks monthly financial performance, runway, and key SaaS metrics.
 */
@Entity
@Table(name = "financial_metrics", schema = "studio")
public class FinancialMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private YearMonth month;

    // Cash & Runway Metrics
    @Column(precision = 19, scale = 2)
    private BigDecimal cashBalance;

    @Column(precision = 19, scale = 2)
    private BigDecimal grossBurnRate;

    @Column(precision = 19, scale = 2)
    private BigDecimal netBurnRate;

    @Column
    private Integer cashRunwayMonths;

    @Column
    private LocalDate zeroCashDate;

    // Revenue Metrics (ARR/MRR)
    @Column(precision = 19, scale = 2)
    private BigDecimal mrr;

    @Column(precision = 19, scale = 2)
    private BigDecimal arr;

    @Column(precision = 19, scale = 2)
    private BigDecimal newMrr;

    @Column(precision = 19, scale = 2)
    private BigDecimal churnedMrr;

    @Column(precision = 19, scale = 2)
    private BigDecimal expansionMrr;

    // Customer Metrics
    @Column
    private Integer totalCustomers;

    @Column
    private Integer newCustomers;

    @Column
    private Integer churnedCustomers;

    // Unit Economics
    @Column(precision = 19, scale = 2)
    private BigDecimal cac;

    @Column(precision = 19, scale = 2)
    private BigDecimal ltv;

    @Column(precision = 10, scale = 2)
    private BigDecimal ltvToCacRatio;

    @Column
    private Integer cacPaybackMonths;

    // Retention Metrics
    @Column(precision = 5, scale = 2)
    private BigDecimal netRevenueRetention;

    @Column(precision = 5, scale = 2)
    private BigDecimal grossRevenueRetention;

    // Metadata
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

    public YearMonth getMonth() { return month; }
    public void setMonth(YearMonth month) { this.month = month; }

    public BigDecimal getCashBalance() { return cashBalance; }
    public void setCashBalance(BigDecimal cashBalance) { this.cashBalance = cashBalance; }

    public BigDecimal getGrossBurnRate() { return grossBurnRate; }
    public void setGrossBurnRate(BigDecimal grossBurnRate) { this.grossBurnRate = grossBurnRate; }

    public BigDecimal getNetBurnRate() { return netBurnRate; }
    public void setNetBurnRate(BigDecimal netBurnRate) { this.netBurnRate = netBurnRate; }

    public Integer getCashRunwayMonths() { return cashRunwayMonths; }
    public void setCashRunwayMonths(Integer cashRunwayMonths) { this.cashRunwayMonths = cashRunwayMonths; }

    public LocalDate getZeroCashDate() { return zeroCashDate; }
    public void setZeroCashDate(LocalDate zeroCashDate) { this.zeroCashDate = zeroCashDate; }

    public BigDecimal getMrr() { return mrr; }
    public void setMrr(BigDecimal mrr) { this.mrr = mrr; }

    public BigDecimal getArr() { return arr; }
    public void setArr(BigDecimal arr) { this.arr = arr; }

    public BigDecimal getNewMrr() { return newMrr; }
    public void setNewMrr(BigDecimal newMrr) { this.newMrr = newMrr; }

    public BigDecimal getChurnedMrr() { return churnedMrr; }
    public void setChurnedMrr(BigDecimal churnedMrr) { this.churnedMrr = churnedMrr; }

    public BigDecimal getExpansionMrr() { return expansionMrr; }
    public void setExpansionMrr(BigDecimal expansionMrr) { this.expansionMrr = expansionMrr; }

    public Integer getTotalCustomers() { return totalCustomers; }
    public void setTotalCustomers(Integer totalCustomers) { this.totalCustomers = totalCustomers; }

    public Integer getNewCustomers() { return newCustomers; }
    public void setNewCustomers(Integer newCustomers) { this.newCustomers = newCustomers; }

    public Integer getChurnedCustomers() { return churnedCustomers; }
    public void setChurnedCustomers(Integer churnedCustomers) { this.churnedCustomers = churnedCustomers; }

    public BigDecimal getCac() { return cac; }
    public void setCac(BigDecimal cac) { this.cac = cac; }

    public BigDecimal getLtv() { return ltv; }
    public void setLtv(BigDecimal ltv) { this.ltv = ltv; }

    public BigDecimal getLtvToCacRatio() { return ltvToCacRatio; }
    public void setLtvToCacRatio(BigDecimal ltvToCacRatio) { this.ltvToCacRatio = ltvToCacRatio; }

    public Integer getCacPaybackMonths() { return cacPaybackMonths; }
    public void setCacPaybackMonths(Integer cacPaybackMonths) { this.cacPaybackMonths = cacPaybackMonths; }

    public BigDecimal getNetRevenueRetention() { return netRevenueRetention; }
    public void setNetRevenueRetention(BigDecimal netRevenueRetention) { this.netRevenueRetention = netRevenueRetention; }

    public BigDecimal getGrossRevenueRetention() { return grossRevenueRetention; }
    public void setGrossRevenueRetention(BigDecimal grossRevenueRetention) { this.grossRevenueRetention = grossRevenueRetention; }

    public LocalDate getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDate createdAt) { this.createdAt = createdAt; }

    public LocalDate getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDate updatedAt) { this.updatedAt = updatedAt; }
}
