package io.github.mgeladzerezo.miniorm.pool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH comparison of pool overhead against HikariCP. Both pools sit on the same stub driver
 * ({@link FakeDatabase}), whose connections do no I/O, so the numbers are the cost of the pool
 * itself: borrow, proxy, state reset, return. Against a real database that cost is dwarfed by
 * the network round trip.
 *
 * <p>Run with: {@code mvnw -pl mini-orm-pool -Pbenchmark test-compile exec:exec
 * -Dbenchmark.args="PoolBenchmark -t 8 -p maxSize=8"} (uncontended) and
 * {@code -Dbenchmark.args="PoolBenchmark -t 16 -p maxSize=4"} (four threads per connection).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class PoolBenchmark {

    @Param({"mini-fair", "mini-unfair", "hikari"})
    public String pool;

    @Param({"8"})
    public int maxSize;

    private DataSource dataSource;
    private AutoCloseable closer;

    @Setup
    public void setUp() {
        FakeDatabase db = new FakeDatabase();
        if (pool.equals("hikari")) {
            HikariConfig config = new HikariConfig();
            config.setDataSource(new FactoryDataSource(db));
            config.setMaximumPoolSize(maxSize);
            config.setMinimumIdle(maxSize);
            config.setConnectionTimeout(30_000);
            HikariDataSource hikari = new HikariDataSource(config);
            dataSource = hikari;
            closer = hikari;
        } else {
            MiniPool mini = new MiniPool(PoolConfig.builder().connectionFactory(db)
                    .maxSize(maxSize).minIdle(maxSize).fair(pool.equals("mini-fair"))
                    .acquireTimeout(Duration.ofSeconds(30)).build());
            dataSource = mini;
            closer = mini;
        }
    }

    @TearDown
    public void tearDown() throws Exception {
        closer.close();
    }

    /** The bare borrow/return cycle. */
    @Benchmark
    public boolean borrowAndReturn() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return c.isClosed();
        }
    }

    /** Borrow, prepare and execute one statement, return: adds statement proxying and tracking. */
    @Benchmark
    public boolean borrowExecuteReturn() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select 1")) {
            return ps.execute();
        }
    }

    /** Adapts the stub driver to the {@link DataSource} that HikariCP wants. */
    private record FactoryDataSource(ConnectionFactory factory) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return factory.create();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return factory.create();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
