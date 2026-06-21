package io.conddo.studio.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * Tracks day-to-day operational activities and progress for Conddo startup.
 * Used for internal team visibility and progress tracking.
 */
@Entity
@Table(name = "operational_activities", schema = "studio")
public class OperationalActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActivityCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityStatus status;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column
    private LocalDate targetDate;

    @Column
    private LocalDate completedDate;

    @Column
    private Integer priority;

    @Column(length = 100)
    private String assignedTo;

    @Column(length = 100)
    private String tags;

    @Column(columnDefinition = "TEXT")
    private String progressNotes;

    @Column(nullable = false, updatable = false)
    private LocalDate createdAt;

    @Column(nullable = false)
    private LocalDate updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDate.now();
        updatedAt = LocalDate.now();
        if (status == null) {
            status = ActivityStatus.PLANNED;
        }
        if (priority == null) {
            priority = 3; // Default medium priority
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDate.now();
        if (status == ActivityStatus.COMPLETED && completedDate == null) {
            completedDate = LocalDate.now();
        }
    }

    public enum ActivityCategory {
        PRODUCT, ENGINEERING, SALES, MARKETING, OPERATIONS, FINANCE, HR, LEGAL
    }

    public enum ActivityStatus {
        PLANNED, IN_PROGRESS, BLOCKED, COMPLETED, CANCELLED
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ActivityCategory getCategory() { return category; }
    public void setCategory(ActivityCategory category) { this.category = category; }

    public ActivityStatus getStatus() { return status; }
    public void setStatus(ActivityStatus status) { this.status = status; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }

    public LocalDate getCompletedDate() { return completedDate; }
    public void setCompletedDate(LocalDate completedDate) { this.completedDate = completedDate; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getProgressNotes() { return progressNotes; }
    public void setProgressNotes(String progressNotes) { this.progressNotes = progressNotes; }

    public LocalDate getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDate createdAt) { this.createdAt = createdAt; }

    public LocalDate getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDate updatedAt) { this.updatedAt = updatedAt; }
}
