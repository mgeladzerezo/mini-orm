package io.github.mgeladzerezo.miniorm.pool;

import static io.github.mgeladzerezo.miniorm.pool.AcquisitionTest.await;
import static io.github.mgeladzerezo.miniorm.pool.AcquisitionTest.sleep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.miniorm.pool.FakeDatabase.FakeConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Orderly shutdown: idle closed, waiters woken, borrowed connections awaited then aborted. */
@Timeout(30)
class ShutdownTest {

    @Test
    void closesIdleConnectionsAndRefusesNewBorrowers() throws Exception {
        FakeDatabase db = new FakeDatabase();
        MiniPool pool = new MiniPool(PoolConfig.builder().connectionFactory(db).maxSize(3).build());
        PoolConcurrencyTest.assertAllPermitsUsable(pool, 3);

        pool.close();
        pool.close(); // idempotent

        assertThat(db.connections).hasSize(3).allMatch(c -> c.closed);
        assertThat(pool.isClosed()).isTrue();
        assertThat(pool.metrics().total()).isZero();
        assertThatThrownBy(pool::getConnection).isInstanceOf(SQLException.class).hasMessageContaining("closed");
    }

    @Test
    void wakesThreadsBlockedInGetConnection() throws Exception {
        FakeDatabase db = new FakeDatabase();
        MiniPool pool = new MiniPool(PoolConfig.builder().connectionFactory(db).maxSize(1)
                .acquireTimeout(Duration.ofSeconds(20)).shutdownTimeout(Duration.ofMillis(100)).build());
        Connection held = pool.getConnection();
        int waiters = 4;
        AtomicInteger sawClosed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(waiters);
        for (int i = 0; i < waiters; i++) {
            Thread.ofPlatform().start(() -> {
                try (Connection c = pool.getConnection()) {
                    throw new AssertionError("no connection expected");
                } catch (SQLException e) {
                    if (e.getMessage().contains("is closed")) {
                        sawClosed.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        await(() -> pool.metrics().waiting() == waiters);

        pool.close();

        assertThat(done.await(5, TimeUnit.SECONDS)).as("waiters did not sit out their 20 s timeout").isTrue();
        assertThat(sawClosed).hasValue(waiters);
        held.close();
    }

    @Test
    void waitsForBorrowedConnectionsThenClosesThemOnReturn() throws Exception {
        FakeDatabase db = new FakeDatabase();
        MiniPool pool = new MiniPool(PoolConfig.builder().connectionFactory(db).maxSize(2)
                .shutdownTimeout(Duration.ofSeconds(10)).build());
        Connection busy = pool.getConnection();
        FakeConnection physical = busy.unwrap(FakeConnection.class);
        Thread.ofPlatform().start(() -> {
            sleep(200);
            assertThat(physical.closed).as("not closed under a borrower inside the grace period").isFalse();
            try {
                busy.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        long start = System.nanoTime();
        pool.close();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(tookMillis).as("waited for the borrower, but not for the whole timeout").isBetween(150L, 5_000L);
        assertThat(physical.closed).isTrue();
        assertThat(db.live).hasValue(0);
    }

    @Test
    void abortsConnectionsStillBorrowedAfterTheShutdownTimeout() throws Exception {
        FakeDatabase db = new FakeDatabase();
        MiniPool pool = new MiniPool(PoolConfig.builder().connectionFactory(db).maxSize(2)
                .shutdownTimeout(Duration.ofMillis(100)).build());
        Connection stuck = pool.getConnection();
        FakeConnection physical = stuck.unwrap(FakeConnection.class);

        pool.close();

        assertThat(physical.closed).isTrue();
        assertThat(db.live).hasValue(0);
        stuck.close(); // the late return is harmless
        assertThat(pool.metrics().total()).isZero();
        assertThat(pool.metrics().connectionsClosed().get(EvictionReason.POOL_CLOSED)).isEqualTo(1);
    }
}
