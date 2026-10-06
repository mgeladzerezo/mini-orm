package io.github.mgeladzerezo.miniorm.pool;

import static io.github.mgeladzerezo.miniorm.pool.AcquisitionTest.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.miniorm.pool.FakeDatabase.FakeConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A pool that loses a permit on some failure path shrinks by one connection each time that
 * path is taken, until it deadlocks. These tests drive each failure path and then check that
 * exactly {@code maxSize} connections can still be borrowed: no fewer (a lost permit) and no
 * more (a permit released twice).
 */
@Timeout(30)
class PermitAccountingTest {

    @Test
    void interruptedWaitersDoNotLeakPermits() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(2).acquireTimeout(Duration.ofSeconds(2)).build())) {
            Connection a = pool.getConnection();
            Connection b = pool.getConnection();

            int waiters = 8;
            List<Thread> threads = new ArrayList<>();
            AtomicInteger failedWithInterruptFlagSet = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(waiters);
            for (int i = 0; i < waiters; i++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    try (Connection c = pool.getConnection()) {
                        throw new AssertionError("should not get a connection");
                    } catch (SQLException e) {
                        if (Thread.currentThread().isInterrupted()) {
                            failedWithInterruptFlagSet.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                }));
            }
            await(() -> pool.metrics().waiting() == waiters);

            threads.forEach(Thread::interrupt);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(failedWithInterruptFlagSet).as("SQLException thrown and interrupt flag preserved")
                    .hasValue(waiters);
            a.close();
            b.close();
            assertExactlyMaxBorrowable(pool, 2);
        }
    }

    @Test
    void failedValidationCostsAConnectionNotAPermit() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(3).validateAfterIdle(Duration.ZERO)
                .acquireTimeout(Duration.ofSeconds(1)).build())) {
            PoolConcurrencyTest.assertAllPermitsUsable(pool, 3); // three idle connections now
            db.killAll();

            try (Connection c = pool.getConnection()) {
                FakeConnection physical = c.unwrap(FakeConnection.class);
                assertThat(physical.valid).as("a dead connection is never handed out").isTrue();
            }

            assertThat(pool.metrics().connectionsClosed().get(EvictionReason.VALIDATION_FAILED)).isEqualTo(3);
            assertThat(db.connections.subList(0, 3)).allMatch(c -> c.closed);
            assertExactlyMaxBorrowable(pool, 3);
        }
    }

    @Test
    void failedCreationDoesNotLeakPermitsOrSlots() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(2).acquireTimeout(Duration.ofMillis(100)).build())) {
            db.refuseConnections = true;
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(pool::getConnection).isInstanceOf(SQLTransientConnectionException.class);
            }
            db.refuseConnections = false;

            assertThat(pool.metrics().acquireTimeouts()).isEqualTo(5);
            assertExactlyMaxBorrowable(pool, 2);
        }
    }

    @Test
    void closingAConnectionTwiceReleasesOnePermit() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(2).acquireTimeout(Duration.ofMillis(100)).build())) {
            Connection held = pool.getConnection();
            Connection c = pool.getConnection();
            c.close();
            c.close();
            c.close();

            assertThat(pool.availablePermits()).isEqualTo(1);
            held.close();
            assertExactlyMaxBorrowable(pool, 2);
        }
    }

    private static void assertExactlyMaxBorrowable(MiniPool pool, int max) throws SQLException {
        assertThat(pool.availablePermits()).isEqualTo(max);
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < max; i++) {
                held.add(pool.getConnection());
            }
            // One more must block and time out: the pool did not grow.
            PoolConfig config = pool.config();
            long start = System.nanoTime();
            assertThatThrownBy(pool::getConnection).isInstanceOf(SQLTransientConnectionException.class);
            assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(config.acquireTimeout().toNanos() / 2);
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        assertThat(pool.availablePermits()).isEqualTo(max);
    }
}
