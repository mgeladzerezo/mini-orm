package io.github.mgeladzerezo.miniorm.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Blocking acquisition: the timeout is honoured, waiters are served in arrival order. */
@Timeout(30)
class AcquisitionTest {

    @Test
    void timesOutWhenThePoolIsExhausted() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(1).acquireTimeout(Duration.ofMillis(250)).build());
             Connection held = pool.getConnection()) {

            long start = System.nanoTime();
            assertThatThrownBy(pool::getConnection)
                    .isInstanceOf(SQLTransientConnectionException.class)
                    .hasMessageContaining("no connection available")
                    .hasMessageContaining("active=1");
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(waitedMillis).as("blocked for the configured timeout, not less").isGreaterThanOrEqualTo(250);
            assertThat(waitedMillis).as("and gave up promptly afterwards").isLessThan(2_000);
            assertThat(pool.metrics().acquireTimeouts()).isEqualTo(1);
            assertThat(pool.availablePermits()).as("the failed attempt holds no permit").isZero();
            assertThat(held.isClosed()).isFalse();
        }
    }

    @Test
    void aWaiterGetsTheConnectionAsSoonAsItIsReturned() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(1).acquireTimeout(Duration.ofSeconds(10)).build())) {
            Connection held = pool.getConnection();
            AtomicReference<Object> result = new AtomicReference<>();
            Thread waiter = Thread.ofPlatform().start(() -> {
                try (Connection c = pool.getConnection()) {
                    result.set(c.unwrap(FakeDatabase.FakeConnection.class));
                } catch (SQLException e) {
                    result.set(e);
                }
            });
            await(() -> pool.metrics().waiting() == 1);

            held.close();
            waiter.join(5_000);

            assertThat(result.get()).as("the waiter reused the single physical connection")
                    .isSameAs(db.connections.getFirst());
            assertThat(db.connections).hasSize(1);
        }
    }

    @Test
    void fairPoolServesWaitersInArrivalOrder() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(1).fair(true).acquireTimeout(Duration.ofSeconds(10)).build())) {
            Connection held = pool.getConnection();
            int waiters = 8;
            List<Integer> served = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(waiters);
            for (int i = 0; i < waiters; i++) {
                int id = i;
                Thread.ofPlatform().start(() -> {
                    try (Connection c = pool.getConnection()) {
                        served.add(id);
                    } catch (SQLException e) {
                        served.add(-1);
                    } finally {
                        done.countDown();
                    }
                });
                // Only start the next waiter once this one is parked, so arrival order is known.
                int expected = i + 1;
                await(() -> pool.metrics().waiting() == expected);
            }

            held.close();

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(served).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        }
    }

    @Test
    void connectionAttemptsAreRetriedUntilTheDatabaseComesBack() throws Exception {
        FakeDatabase db = new FakeDatabase();
        db.refuseConnections = true;
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(2).acquireTimeout(Duration.ofSeconds(10)).build())) {
            Thread.ofPlatform().start(() -> {
                sleep(150);
                db.refuseConnections = false;
            });

            try (Connection c = pool.getConnection()) {
                assertThat(c.isValid(1)).isTrue();
            }
            assertThat(pool.metrics().total()).isEqualTo(1);
        }
    }

    @Test
    void failsWithTheCauseWhenTheDatabaseStaysDown() throws Exception {
        FakeDatabase db = new FakeDatabase();
        db.refuseConnections = true;
        try (MiniPool pool = new MiniPool(PoolConfig.builder()
                .connectionFactory(db).maxSize(2).acquireTimeout(Duration.ofMillis(200)).build())) {

            assertThatThrownBy(pool::getConnection)
                    .isInstanceOf(SQLTransientConnectionException.class)
                    .hasMessageContaining("connection refused")
                    .hasCauseInstanceOf(SQLException.class);

            assertThat(pool.metrics().total()).as("the reserved slot was given back").isZero();
            assertThat(pool.availablePermits()).isEqualTo(2);
            db.refuseConnections = false;
            PoolConcurrencyTest.assertAllPermitsUsable(pool, 2);
        }
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10 s");
            }
            sleep(1);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
