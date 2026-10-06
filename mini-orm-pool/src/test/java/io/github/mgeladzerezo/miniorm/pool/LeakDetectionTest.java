package io.github.mgeladzerezo.miniorm.pool;

import static io.github.mgeladzerezo.miniorm.pool.AcquisitionTest.await;
import static io.github.mgeladzerezo.miniorm.pool.AcquisitionTest.sleep;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A connection held past the threshold is reported once, with the borrower's stack trace. */
@Timeout(30)
class LeakDetectionTest {

    private final List<PoolListener.Leak> leaks = new CopyOnWriteArrayList<>();
    private final PoolListener listener = new PoolListener() {
        @Override
        public void onLeakDetected(Leak leak) {
            leaks.add(leak);
        }
    };

    private MiniPool pool(Duration threshold) {
        return new MiniPool(PoolConfig.builder().connectionFactory(new FakeDatabase()).maxSize(2)
                .poolName("leaky").leakDetectionThreshold(threshold).listener(listener).build());
    }

    private Connection borrowAndForget(MiniPool pool) throws SQLException {
        return pool.getConnection();
    }

    @Test
    void reportsTheCallSiteOfTheBorrowerOnce() throws Exception {
        try (MiniPool pool = pool(Duration.ofMillis(60))) {
            Connection leaked = borrowAndForget(pool);

            await(() -> !leaks.isEmpty());
            sleep(200); // several more scans must not report the same borrow again

            assertThat(leaks).hasSize(1);
            PoolListener.Leak leak = leaks.getFirst();
            assertThat(leak.poolName()).isEqualTo("leaky");
            assertThat(leak.threadName()).isEqualTo(Thread.currentThread().getName());
            assertThat(leak.heldFor()).isGreaterThanOrEqualTo(Duration.ofMillis(60));
            assertThat(leak.borrowedAt().getStackTrace())
                    .as("the stack trace points at the code that took the connection")
                    .anyMatch(frame -> frame.getMethodName().equals("borrowAndForget")
                            && frame.getClassName().equals(LeakDetectionTest.class.getName()));
            assertThat(pool.metrics().leaksDetected()).isEqualTo(1);

            leaked.close();
            assertThat(pool.metrics().active()).isZero();
        }
    }

    @Test
    void connectionsReturnedInTimeAreNotReported() throws Exception {
        try (MiniPool pool = pool(Duration.ofMillis(300))) {
            for (int i = 0; i < 20; i++) {
                try (Connection c = pool.getConnection()) {
                    sleep(5);
                }
            }
            sleep(400);
            assertThat(leaks).isEmpty();
        }
    }
}
