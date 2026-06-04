package io.conddo.studio.sse;

import java.util.UUID;

/**
 * Spring application events for Studio job lifecycle transitions and staff
 * notifications (Infrastructure §13.2). Published from {@link io.conddo.studio.jobs.JobService}
 * inside its {@code @Transactional} methods and consumed by {@link SseService}
 * with {@code @TransactionalEventListener(AFTER_COMMIT)} — broadcasts only fire
 * once the DB write has committed, so subscribers re-querying on receipt see the
 * new state.
 *
 * <p>Each variant carries the minimum the board UI needs to react (mark a card
 * claimed, move to QA, drop from "available"), with enough identifiers for the
 * client to fetch the full detail if it wants more.
 */
public sealed interface JobLifecycleEvent {

    UUID jobId();

    String jobNumber();

    /** A new job entered AVAILABLE — broadcast to everyone with matching skill. */
    record JobCreated(UUID jobId, String jobNumber, String jobTypeId, String status,
                      String slaTone) implements JobLifecycleEvent {
    }

    /** A staff member claimed an AVAILABLE job — broadcast so others remove it from their queue. */
    record JobClaimed(UUID jobId, String jobNumber, String jobTypeId, UUID staffId) implements JobLifecycleEvent {
    }

    /** A staff member started work (ASSIGNED → IN_PROGRESS). */
    record JobStarted(UUID jobId, String jobNumber, UUID staffId) implements JobLifecycleEvent {
    }

    /** A staff member submitted for QA — broadcast to QA_REVIEWER. */
    record JobSubmitted(UUID jobId, String jobNumber, String jobTypeId, UUID staffId) implements JobLifecycleEvent {
    }

    /** QA approved — direct send to the assigned producer. */
    record JobApproved(UUID jobId, String jobNumber, UUID assignedTo) implements JobLifecycleEvent {
    }

    /** QA returned for revision — direct send to the assigned producer. */
    record JobRevisionRequested(UUID jobId, String jobNumber, UUID assignedTo, String feedback)
            implements JobLifecycleEvent {
    }

    /** Admin reassigned — direct send to the new assignee. */
    record JobReassigned(UUID jobId, String jobNumber, UUID newStaffId) implements JobLifecycleEvent {
    }

    /** Job escalated (manual or SLA-driven) — broadcast to TEAM_LEAD + ADMIN. */
    record JobEscalated(UUID jobId, String jobNumber, String reason) implements JobLifecycleEvent {
    }

    /** SLA was extended — direct send to the assigned producer if any. */
    record JobSlaExtended(UUID jobId, String jobNumber, int addedHours, UUID assignedTo)
            implements JobLifecycleEvent {
    }

    /** A new in-app notification was created — direct send to its target staff. */
    record NotificationCreated(UUID jobId, String jobNumber, UUID staffId, UUID notificationId,
                               String type, String title, String message) implements JobLifecycleEvent {
        // jobNumber may be null for platform-admin originated events (no job context).
    }

    // ----- Platform Admin (§23 Phase 13b) ------------------------------------
    // These don't carry a job — jobId/jobNumber are filled with sentinels so the
    // sealed interface contract still holds.

    /**
     * A Studio ADMIN changed a tenant's status (SUSPENDED, ACTIVE, DELETED).
     * Broadcast to TEAM_LEAD + ADMIN so peers know during a shared shift.
     */
    record PlatformTenantStatusChanged(UUID jobId, String jobNumber, UUID tenantId, String newStatus,
                                       UUID actorStaffId, java.time.OffsetDateTime at)
            implements JobLifecycleEvent {

        public static PlatformTenantStatusChanged of(UUID tenantId, String newStatus, UUID actor) {
            return new PlatformTenantStatusChanged(NIL, "", tenantId, newStatus, actor,
                    java.time.OffsetDateTime.now());
        }
    }

    /**
     * A Studio ADMIN deactivated (or soft-deleted) a tenant user — refresh
     * tokens revoked, in-flight sessions get bounced on next request.
     */
    record PlatformUserDeactivated(UUID jobId, String jobNumber, UUID userId, UUID tenantId,
                                   UUID actorStaffId, java.time.OffsetDateTime at)
            implements JobLifecycleEvent {

        public static PlatformUserDeactivated of(UUID userId, UUID tenantId, UUID actor) {
            return new PlatformUserDeactivated(NIL, "", userId, tenantId, actor,
                    java.time.OffsetDateTime.now());
        }
    }

    /** Sentinel UUID for platform-admin events (no job context). */
    UUID NIL = new UUID(0L, 0L);
}
