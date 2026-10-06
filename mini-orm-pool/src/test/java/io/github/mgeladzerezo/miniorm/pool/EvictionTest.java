package io.github.mgeladzerezo.miniorm.pool;

import static io.github.mgeladzerezo.miniorm.pool.AcquisitionTest.await;
import static io.github.mgeladzerezo.miniorm.pool.AcquisitionTest.sleep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.miniorm.pool.FakeDatabase.FakeConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Housekeeping (max lifetime, idle timeout, min idle) and eviction of broken connections. */
@Timeout(30)
class EvictionTest {

    private static PoolConfig.Builder fastHousekeeping(FakeDatabase db) {
        return PoolConfig.builder().connectionFactory(db).maxSize(4)
                .housekeepingInterval(Duration.ofMillis(10)).acquireTimeout(Duration.ofSeconds(2));
    }

    @Test
    void housekeeperOpensMinIdleConnectionsWithoutAnyBorrower() {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(fastHousekeeping(db).minIdle(3).build())) {
            await(() -> pool.metrics().idle() == 3);
            sleep(50);
            assertThat(pool.metrics().total()).as("it stops at minIdle").isEqualTo(3);
        }
    }

    @Test
    void idleConnectionsAboveMinIdleAreClosedAfterTheIdleTimeout() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(fastHousekeeping(db).minIdle(1)
                .idleTimeout(Duration.ofMillis(100)).build())) {
            PoolConcurrencyTest.assertAllPermitsUsable(pool, 4);
            assertThat(pool.metrics().idle()).isEqualTo(4);

            await(() -> pool.metrics().idle() == 1);
            sleep(150);

            PoolMetrics metrics = pool.metrics();
            assertThat(metrics.idle()).as("minIdle is kept").isEqualTo(1);
            assertThat(metrics.connectionsClosed().get(EvictionReason.IDLE_TIMEOUT)).isEqualTo(3);
            assertThat(db.live).hasValue(1);
        }
    }

    @Test
    void idleConnectionsPastMaxLifetimeAreReplaced() {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(fastHousekeeping(db).minIdle(2)
                .maxLifetime(Duration.ofMillis(120)).build())) {
            await(() -> pool.metrics().idle() == 2);
            FakeConnection first = db.connections.getFirst();

            await(() -> pool.metrics().connectionsClosed().get(EvictionReason.MAX_LIFETIME) >= 2);
            await(() -> pool.metrics().idle() == 2);

            assertThat(first.closed).isTrue();
            assertThat(db.live).hasValue(2);
        }
    }

    @Test
    void aBorrowedConnectionPastMaxLifetimeIsClosedOnReturnNotWhileInUse() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(fastHousekeeping(db).maxLifetime(Duration.ofMillis(60)).build())) {
            Connection c = pool.getConnection();
            FakeConnection physical = c.unwrap(FakeConnection.class);
            sleep(150);
            assertThat(physical.closed).as("never closed under the borrower").isFalse();

            c.close();

            assertThat(physical.closed).isTrue();
            assertThat(pool.metrics().connectionsClosed().get(EvictionReason.MAX_LIFETIME)).isEqualTo(1);
            assertThat(pool.metrics().total()).isZero();
        }
    }

    @Test
    void fatalSqlStateFromAStatementEvictsTheConnection() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(fastHousekeeping(db).build())) {
            FakeConnection physical;
            try (Connection c = pool.getConnection()) {
                physical = c.unwrap(FakeConnection.class);
                physical.failNextExecute = new SQLException("server closed the connection", "08006");
                PreparedStatement ps = c.prepareStatement("select 1");
                assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class).hasMessageContaining("server closed");
            }

            assertThat(physical.closed).isTrue();
            assertThat(pool.metrics().connectionsClosed().get(EvictionReason.BROKEN)).isEqualTo(1);
            try (Connection next = pool.getConnection()) {
                assertThat(next.unwrap(FakeConnection.class)).isNotSameAs(physical);
            }
        }
    }

    @Test
    void ordinarySqlErrorsKeepTheConnection() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(fastHousekeeping(db).build())) {
            FakeConnection physical;
            try (Connection c = pool.getConnection()) {
                physical = c.unwrap(FakeConnection.class);
                physical.failNextExecute = new SQLException("duplicate key", "23505");
                Statement s = c.createStatement();
                assertThatThrownBy(() -> s.executeUpdate("insert ...")).isInstanceOf(SQLException.class);
            }

            assertThat(physical.closed).isFalse();
            try (Connection next = pool.getConnection()) {
                assertThat(next.unwrap(FakeConnection.class)).isSameAs(physical);
            }
        }
    }

    @Test
    void classifiesSqlStates() {
        Set<String> none = Set.of();
        assertThat(FatalSqlStates.isFatal(new SQLException("x", "08003"), none)).isTrue();
        assertThat(FatalSqlStates.isFatal(new SQLException("x", "57P01"), none)).isTrue();
        assertThat(FatalSqlStates.isFatal(new SQLNonTransientConnectionException("x"), none)).isTrue();
        assertThat(FatalSqlStates.isFatal(new SQLException("x", "23505"), none)).isFalse();
        assertThat(FatalSqlStates.isFatal(new SQLException("x", "40001"), none)).isFalse();
        assertThat(FatalSqlStates.isFatal(new SQLException("x"), none)).isFalse();
        assertThat(FatalSqlStates.isFatal(new SQLException("x", "XX000"), Set.of("XX000"))).isTrue();

        SQLException chained = new SQLException("batch failed", "40000");
        chained.setNextException(new SQLException("io error", "08006"));
        assertThat(FatalSqlStates.isFatal(chained, none)).as("looks at chained exceptions").isTrue();
    }
}
