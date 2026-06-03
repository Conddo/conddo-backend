package io.conddo.studio.sse;

import io.conddo.studio.domain.Staff;
import io.conddo.studio.repository.StaffRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSE hub load test (Slice G / Phase 10 — Infrastructure §13.4 SLO).
 * Exercises the connection bookkeeping and broadcast plumbing under
 * 50 concurrent connections and a broadcast burst — verifies the
 * ConcurrentHashMap + CopyOnWriteArrayList structures handle the
 * concurrency without losing connections or throwing under contention.
 *
 * <p>This is the SSE half of the §13.4 SLO: the Studio board must
 * support every active staff member on a single instance without
 * cross-thread surprises. A regression here (e.g. switching to a
 * non-thread-safe collection) would manifest as flakey board updates.
 */
class SseServiceLoadTest {

    private static final int CONCURRENT_STAFF = 50;
    private static final int BROADCAST_ROUNDS = 20;

    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final SseService service = new SseService(staffRepository);

    @Test
    void fiftyConcurrentSubscribersAreAllTracked() throws InterruptedException {
        List<UUID> staffIds = seedStaff(CONCURRENT_STAFF, "DEVELOPER", List.of("WEBSITE_BUILD"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_STAFF);
        AtomicInteger failures = new AtomicInteger();

        for (UUID id : staffIds) {
            pool.submit(() -> {
                try {
                    start.await();
                    SseEmitter emitter = service.subscribe(id);
                    assertNotNull(emitter);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "50 subscribes should complete within 10s");
        pool.shutdown();

        assertEquals(0, failures.get(), "no subscribe should throw");
        assertEquals(CONCURRENT_STAFF, service.connectionCount(),
                "every concurrent subscribe should be tracked exactly once");
    }

    @Test
    void broadcastBurstUnderLoadDoesNotThrow() throws InterruptedException {
        List<UUID> staffIds = seedStaff(CONCURRENT_STAFF, "DEVELOPER", List.of("WEBSITE_BUILD"));
        for (UUID id : staffIds) {
            service.subscribe(id);
        }
        assertEquals(CONCURRENT_STAFF, service.connectionCount());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(BROADCAST_ROUNDS);
        AtomicInteger failures = new AtomicInteger();

        for (int round = 0; round < BROADCAST_ROUNDS; round++) {
            final int r = round;
            pool.submit(() -> {
                try {
                    JobLifecycleEvent.JobCreated event = new JobLifecycleEvent.JobCreated(
                            UUID.randomUUID(), "WB-" + r, "WEBSITE_BUILD", "AVAILABLE", "GREEN");
                    service.broadcastToSkill("WEBSITE_BUILD", "job.created", event);
                    service.broadcastToRole("DEVELOPER", "job.created", event);
                    service.heartbeat();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(10, TimeUnit.SECONDS), "broadcast burst should complete within 10s");
        pool.shutdown();
        assertEquals(0, failures.get(), "concurrent broadcasts should not throw");
        // Connections remain registered — the in-memory emitters queue events while no
        // handler is bound; cleanup only fires on real wire errors.
        assertEquals(CONCURRENT_STAFF, service.connectionCount(),
                "broadcasts under load must not lose connections");
    }

    @Test
    void mixedSubscribeAndBroadcastDoesNotDeadlockOrThrow() throws InterruptedException {
        List<UUID> firstHalf = seedStaff(CONCURRENT_STAFF / 2, "DEVELOPER", List.of("WEBSITE_BUILD"));
        List<UUID> secondHalf = seedStaff(CONCURRENT_STAFF / 2, "QA_REVIEWER", List.of());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(CONCURRENT_STAFF + BROADCAST_ROUNDS);
        AtomicInteger failures = new AtomicInteger();

        // Half the threads subscribe; the other half broadcast — concurrently.
        for (UUID id : firstHalf) {
            pool.submit(() -> { run(failures, done, () -> service.subscribe(id)); });
        }
        for (UUID id : secondHalf) {
            pool.submit(() -> { run(failures, done, () -> service.subscribe(id)); });
        }
        for (int i = 0; i < BROADCAST_ROUNDS; i++) {
            final int r = i;
            pool.submit(() -> {
                run(failures, done, () -> {
                    service.broadcastToSkill("WEBSITE_BUILD", "job.created",
                            new JobLifecycleEvent.JobCreated(UUID.randomUUID(), "WB-" + r,
                                    "WEBSITE_BUILD", "AVAILABLE", "GREEN"));
                    service.broadcastToRole("QA_REVIEWER", "job.submitted",
                            new JobLifecycleEvent.JobSubmitted(UUID.randomUUID(), "WB-" + r,
                                    "WEBSITE_BUILD", firstHalf.isEmpty() ? UUID.randomUUID() : firstHalf.get(0)));
                    return null;
                });
            });
        }

        assertTrue(done.await(15, TimeUnit.SECONDS),
                "mixed subscribe+broadcast must not deadlock");
        pool.shutdown();
        assertEquals(0, failures.get());
        assertEquals(CONCURRENT_STAFF, service.connectionCount());
    }

    // ----- helpers ------------------------------------------------------------

    private List<UUID> seedStaff(int count, String role, List<String> skills) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            Staff staff = new Staff("staff" + i + "@studio.test", "hash",
                    "Staff " + i, role, new ArrayList<>(skills));
            setId(staff, id);
            when(staffRepository.findById(id)).thenReturn(Optional.of(staff));
            ids.add(id);
        }
        return ids;
    }

    private static void run(AtomicInteger failures, CountDownLatch done,
                            java.util.concurrent.Callable<?> body) {
        try {
            body.call();
        } catch (Exception e) {
            failures.incrementAndGet();
        } finally {
            done.countDown();
        }
    }

    private static void setId(Staff staff, UUID id) {
        try {
            Field f = Staff.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(staff, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
