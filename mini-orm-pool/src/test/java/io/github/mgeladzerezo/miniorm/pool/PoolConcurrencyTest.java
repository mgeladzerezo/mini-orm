package io.github.mgeladzerezo.miniorm.pool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mgeladzerezo.miniorm.pool.FakeDatabase.FakeConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Hammers small pools from many threads. Each physical connection carries an in-use marker
 * that a worker sets on borrow and clears before returning; a compare-and-set that fails means
 * two threads held the same connection at the same time.
 */
@Timeout(60)
class PoolConcurrencyTest {

    private static final int MAX = 4;
    private static final int THREADS = 32;
    private static final int ITERATIONS = 2_000;

    @ParameterizedTest(name = "fair={0} virtualThreads={1}")
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void noConnectionIsHandedToTwoThreadsAtOnce(boolean fair, boolean virtualThreads) throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(MAX).fair(fair).acquireTimeout(Duration.ofSeconds(30)).build())) {
            AtomicInteger doubleUse = new AtomicInteger();

            hammer(virtualThreads, THREADS, ITERATIONS, () -> {
                try (Connection c = pool.getConnection()) {
                    FakeConnection physical = c.unwrap(FakeConnection.class);
                    if (!physical.inUse.compareAndSet(false, true)) {
                        doubleUse.incrementAndGet();
                    }
                    if (ThreadLocalRandom.current().nextInt(8) == 0) {
                        Thread.yield(); // widen the window in which a second holder could appear
                    }
                    physical.inUse.set(false);
                }
            });

            PoolMetrics metrics = pool.metrics();
            assertThat(doubleUse).as("borrows that saw a connection already in use").hasValue(0);
            assertThat(db.peakLive).as("physical connections open at once").hasValueLessThanOrEqualTo(MAX);
            assertThat(metrics.acquired()).isEqualTo((long) THREADS * ITERATIONS);
            assertThat(metrics.active()).isZero();
            assertThat(metrics.waiting()).isZero();
            assertThat(pool.availablePermits()).as("permits after quiescence").isEqualTo(MAX);
            assertThat(metrics.acquireTime().count()).isEqualTo((long) THREADS * ITERATIONS);
        }
    }

    /**
     * Same invariant while everything that can go wrong does: connections die between uses,
     * statements fail with fatal SQLStates, connections expire, the housekeeper opens and
     * closes connections concurrently, and some workers are interrupted while they wait.
     */
    @Test
    void invariantsHoldUnderFailuresChurnAndInterrupts() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(MAX).minIdle(MAX)
                .validateAfterIdle(Duration.ZERO)
                .maxLifetime(Duration.ofMillis(20))
                .idleTimeout(Duration.ofMillis(5))
                .housekeepingInterval(Duration.ofMillis(1))
                .acquireTimeout(Duration.ofSeconds(30)).build())) {
            AtomicInteger doubleUse = new AtomicInteger();
            AtomicInteger interruptedBorrows = new AtomicInteger();

            hammer(false, THREADS, 1_000, () -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                if (random.nextInt(50) == 0) {
                    Thread.currentThread().interrupt(); // getConnection must fail cleanly
                }
                try (Connection c = pool.getConnection()) {
                    FakeConnection physical = c.unwrap(FakeConnection.class);
                    if (!physical.inUse.compareAndSet(false, true)) {
                        doubleUse.incrementAndGet();
                    }
                    int dice = random.nextInt(20);
                    if (dice == 0) {
                        physical.valid = false; // dies while idle; the next borrower must not get it
                    } else if (dice == 1) {
                        physical.failNextExecute = new SQLException("terminated", "57P01");
                        try (Statement s = c.createStatement()) {
                            s.execute("select 1");
                        } catch (SQLException expected) {
                            // the pool must evict this connection on close
                        }
                    }
                    physical.inUse.set(false);
                } catch (SQLException e) {
                    if (Thread.interrupted()) {
                        interruptedBorrows.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
            });

            assertThat(doubleUse).hasValue(0);
            assertThat(interruptedBorrows).as("the interrupt path was exercised").hasValueGreaterThan(0);
            assertThat(db.peakLive).as("physical connections open at once").hasValueLessThanOrEqualTo(MAX);
            assertThat(pool.metrics().active()).isZero();
            assertThat(pool.availablePermits()).as("no permit lost or invented").isEqualTo(MAX);
            assertThat(pool.metrics().connectionsClosed().get(EvictionReason.BROKEN)).isPositive();
            assertThat(pool.metrics().connectionsClosed().get(EvictionReason.VALIDATION_FAILED)).isPositive();
            assertAllPermitsUsable(pool, MAX);
        }
    }

    /** Borrows {@code max} connections at once; fails if a permit went missing. */
    static void assertAllPermitsUsable(MiniPool pool, int max) throws SQLException {
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < max; i++) {
                held.add(pool.getConnection());
            }
            assertThat(pool.metrics().active()).isEqualTo(max);
            assertThat(pool.availablePermits()).isZero();
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        assertThat(pool.availablePermits()).isEqualTo(max);
    }

    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    /** Runs {@code work} {@code iterations} times on each of {@code threads} threads, started together. */
    static void hammer(boolean virtualThreads, int threads, int iterations, Work work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ExecutorService executor = virtualThreads
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations && failure.get() == null; i++) {
                            work.run();
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(50, TimeUnit.SECONDS)).as("workers finished").isTrue();
        }
        if (failure.get() != null) {
            throw new AssertionError("worker failed", failure.get());
        }
    }
}
