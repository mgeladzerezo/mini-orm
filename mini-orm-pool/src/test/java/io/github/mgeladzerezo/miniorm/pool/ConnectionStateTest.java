package io.github.mgeladzerezo.miniorm.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the proxy connection guarantees to the <em>next</em> borrower, checked against a real
 * driver (H2): clean session state, no inherited transaction, no leaked statements.
 */
@Timeout(60)
class ConnectionStateTest {

    private MiniPool pool;

    @BeforeEach
    void setUp() throws SQLException {
        // A single connection, so the second borrow is guaranteed to see the first one's leftovers.
        pool = new MiniPool(PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa").password("")
                .maxSize(1).acquireTimeout(Duration.ofSeconds(5)).build());
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table account (id int primary key, balance int not null)");
            s.execute("insert into account values (1, 100)");
        }
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    @Test
    void sessionStateIsRestoredForTheNextBorrower() throws SQLException {
        Connection physical;
        try (Connection c = pool.getConnection()) {
            physical = c.unwrap(org.h2.jdbc.JdbcConnection.class);
            c.setAutoCommit(false);
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        }

        try (Connection c = pool.getConnection()) {
            assertThat(c.unwrap(org.h2.jdbc.JdbcConnection.class)).as("same physical connection").isSameAs(physical);
            assertThat(c.getAutoCommit()).isTrue();
            assertThat(physical.getAutoCommit()).isTrue();
            assertThat(c.isReadOnly()).isFalse();
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
    }

    @Test
    void anOpenTransactionIsRolledBackNotCommittedOnReturn() throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.executeUpdate("update account set balance = 0 where id = 1");
            // returned without commit
        }

        assertThat(balance()).as("uncommitted work must not survive the return").isEqualTo(100);
    }

    @Test
    void committedWorkSurvives() throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.executeUpdate("update account set balance = 42 where id = 1");
            c.commit();
        }

        assertThat(balance()).isEqualTo(42);
    }

    @Test
    void statementsLeftOpenAreClosedOnReturn() throws SQLException {
        Statement leakedStatement;
        PreparedStatement leakedPrepared;
        ResultSet leakedResult;
        try (Connection c = pool.getConnection()) {
            leakedStatement = c.createStatement();
            leakedPrepared = c.prepareStatement("select balance from account where id = ?");
            leakedPrepared.setInt(1, 1);
            leakedResult = leakedPrepared.executeQuery();
            assertThat(leakedStatement.getConnection()).as("statements point back at the proxy").isSameAs(c);
        }

        assertThat(leakedStatement.isClosed()).isTrue();
        assertThat(leakedPrepared.isClosed()).isTrue();
        assertThat(leakedResult.isClosed()).isTrue();
    }

    @Test
    void aClosedProxyCannotTouchTheConnectionAnymore() throws SQLException {
        Connection stale = pool.getConnection();
        stale.close();

        assertThat(stale.isClosed()).isTrue();
        assertThat(stale.isValid(1)).isFalse();
        assertThatThrownBy(stale::createStatement).isInstanceOf(SQLException.class).hasMessageContaining("closed");
        assertThatThrownBy(() -> stale.setAutoCommit(false)).isInstanceOf(SQLException.class);
        assertThatThrownBy(stale::commit).isInstanceOf(SQLException.class);

        // The physical connection now belongs to someone else and is unaffected by the stale handle.
        try (Connection current = pool.getConnection()) {
            assertThat(current.getAutoCommit()).isTrue();
            assertThat(current.isValid(1)).isTrue();
        }
    }

    @Test
    void concurrentTransfersOnARealDatabaseBalance() throws Exception {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("insert into account values (2, 100)");
        }
        AtomicInteger moved = new AtomicInteger();
        PoolConcurrencyTest.hammer(false, 8, 50, () -> {
            try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
                c.setAutoCommit(false);
                s.executeUpdate("update account set balance = balance - 1 where id = 1");
                s.executeUpdate("update account set balance = balance + 1 where id = 2");
                if (moved.incrementAndGet() % 5 == 0) {
                    return; // every fifth transfer is abandoned without commit
                }
                c.commit();
            }
        });

        try (Connection c = pool.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select sum(balance), min(balance) from account")) {
            rs.next();
            assertThat(rs.getInt(1)).as("money is conserved").isEqualTo(200);
            assertThat(rs.getInt(2)).as("abandoned transfers were rolled back: 400 attempts, 80 abandoned")
                    .isEqualTo(100 - 320);
        }
    }

    private int balance() throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select balance from account where id = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
