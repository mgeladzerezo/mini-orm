package io.github.mgeladzerezo.miniorm.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Recovery against a real PostgreSQL server whose backends are killed with
 * {@code pg_terminate_backend}, which is what a failover or an administrator does to a pool.
 */
@Testcontainers
@Timeout(120)
class PostgresRecoveryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final int MAX = 5;

    private MiniPool pool(Duration validateAfterIdle) {
        return new MiniPool(PoolConfig.builder()
                .jdbcUrl(POSTGRES.getJdbcUrl()).username(POSTGRES.getUsername()).password(POSTGRES.getPassword())
                .maxSize(MAX).validateAfterIdle(validateAfterIdle).acquireTimeout(Duration.ofSeconds(10)).build());
    }

    @Test
    void recoversAfterTheServerDropsEveryIdleConnection() throws Exception {
        try (MiniPool pool = pool(Duration.ZERO)) {
            Set<Integer> before = backendPids(pool);
            assertThat(before).hasSize(MAX);

            assertThat(terminateAllOtherBackends()).isGreaterThanOrEqualTo(MAX);

            // Every pooled connection is now dead. Each borrow must still succeed, first time.
            for (int i = 0; i < 20; i++) {
                try (Connection c = pool.getConnection(); Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("select 1")) {
                    assertThat(rs.next()).isTrue();
                }
            }
            Set<Integer> after = backendPids(pool);

            PoolMetrics metrics = pool.metrics();
            assertThat(after).as("all connections were replaced").hasSize(MAX).doesNotContainAnyElementsOf(before);
            assertThat(metrics.connectionsClosed().get(EvictionReason.VALIDATION_FAILED)).isEqualTo(MAX);
            assertThat(metrics.total()).isLessThanOrEqualTo(MAX);
            assertThat(pool.availablePermits()).isEqualTo(MAX);
        }
    }

    @Test
    void aConnectionKilledWhileBorrowedIsEvictedAfterItsStatementFails() throws Exception {
        try (MiniPool pool = pool(Duration.ZERO)) {
            Connection c = pool.getConnection();
            int pid = backendPid(c);
            terminateAllOtherBackends();

            Statement s = c.createStatement();
            assertThatThrownBy(() -> s.executeQuery("select 1"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState())
                            .as("PostgreSQL reports admin shutdown or a broken connection")
                            .matches("57P01|08\\d{3}"));
            c.close();

            assertThat(pool.metrics().connectionsClosed().get(EvictionReason.BROKEN)).isEqualTo(1);
            try (Connection next = pool.getConnection()) {
                assertThat(backendPid(next)).isNotEqualTo(pid);
            }
            assertThat(pool.availablePermits()).isEqualTo(MAX);
        }
    }

    /**
     * With validation skipped for recently used connections (the default trades a round trip
     * per borrow for this window), a borrower can receive a dead connection. The contract is
     * then: its statement fails, the connection is evicted, and at most {@code MAX} such
     * failures happen before the pool is clean again.
     */
    @Test
    void withoutBorrowValidationDeadConnectionsAreFlushedOutByStatementFailures() throws Exception {
        try (MiniPool pool = pool(Duration.ofMinutes(1))) {
            backendPids(pool);
            terminateAllOtherBackends();

            int failures = 0;
            for (int i = 0; i < 3 * MAX; i++) {
                try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
                    s.execute("select 1");
                } catch (SQLException e) {
                    failures++;
                }
            }

            assertThat(failures).isBetween(1, MAX);
            assertThat(pool.metrics().connectionsClosed().get(EvictionReason.BROKEN)).isEqualTo(failures);
            try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
                assertThat(s.execute("select 1")).isTrue();
            }
        }
    }

    /** Borrows every connection at once and returns the server-side process ids behind them. */
    private Set<Integer> backendPids(MiniPool pool) throws SQLException {
        List<Connection> held = new ArrayList<>();
        Set<Integer> pids = new HashSet<>();
        try {
            for (int i = 0; i < MAX; i++) {
                Connection c = pool.getConnection();
                held.add(c);
                pids.add(backendPid(c));
            }
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        return pids;
    }

    private static int backendPid(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int terminateAllOtherBackends() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = admin.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from (select pg_terminate_backend(pid) "
                     + "from pg_stat_activity where pid <> pg_backend_pid() and datname = current_database() "
                     + "and backend_type = 'client backend') t")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
