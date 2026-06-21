package io.conddo.studio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Basic accounting entry for internal Conddo financial tracking.
 * Supports accrual accounting with separate cash vs revenue tracking.
 */
@Entity
@Table(name = "accounting_entries", schema = "studio")
public class AccountingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String entryNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntryType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDate entryDate;

    @Column
    private LocalDate recognizedDate;

    @Column(length = 100)
    private String relatedEntity;

    @Column(length = 100)
    private String reference;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, updatable = false)
    private LocalDate createdAt;

    @Column(nullable = false)
    private LocalDate updatedAt;

    @Column(length = 500)
    private String notes;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDate.now();
        updatedAt = LocalDate.now();
        if (entryNumber == null || entryNumber.isBlank()) {
            entryNumber = generateEntryNumber();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDate.now();
    }

    private String generateEntryNumber() {
        return "ACC-" + LocalDate.now().getYear() + "-" + System.currentTimeMillis() % 10000;
    }

    public enum EntryType {
        REVENUE, EXPENSE, CASH_IN, CASH_OUT, ACCRUAL, DEFERRAL
    }

    public enum Category {
        // Revenue categories
        SUBSCRIPTION, ONE_TIME, EXPANSION, SERVICE,
        // Expense categories
        PAYROLL, INFRASTRUCTURE, MARKETING, SALES, LEGAL, OFFICE, SOFTWARE,
        // Cash categories
        INVESTMENT, LOAN, GRANT, REFUND, PAYMENT
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEntryNumber() { return entryNumber; }
    public void setEntryNumber(String entryNumber) { this.entryNumber = entryNumber; }

    public EntryType getType() { return type; }
    public void setType(EntryType type) { this.type = type; }

    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }

    public LocalDate getRecognizedDate() { return recognizedDate; }
    public void setRecognizedDate(LocalDate recognizedDate) { this.recognizedDate = recognizedDate; }

    public String getRelatedEntity() { return relatedEntity; }
    public void setRelatedEntity(String relatedEntity) { this.relatedEntity = relatedEntity; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDate createdAt) { this.createdAt = createdAt; }

    public LocalDate getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDate updatedAt) { this.updatedAt = updatedAt; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
