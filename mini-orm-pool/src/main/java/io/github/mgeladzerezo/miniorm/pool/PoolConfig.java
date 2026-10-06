package io.github.mgeladzerezo.miniorm.pool;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Immutable pool settings. Build one with {@link #builder()}.
 *
 * @param poolName               used in thread names, log lines and exception messages
 * @param connectionFactory      opens physical connections
 * @param maxSize                hard upper bound on physical connections
 * @param minIdle                idle connections the housekeeper keeps ready (0 = create on demand)
 * @param fair                   hand connections to waiting threads in arrival order
 * @param acquireTimeout         longest {@code getConnection()} may block
 * @param validationTimeout      budget for {@code Connection.isValid} on borrow
 * @param validateAfterIdle      connections returned more recently than this skip validation
 * @param maxLifetime            connections older than this are retired (zero = never)
 * @param idleTimeout            idle connections above {@code minIdle} are closed after this (zero = never)
 * @param housekeepingInterval   period of the eviction / top-up task
 * @param leakDetectionThreshold report connections held longer than this (zero = off)
 * @param shutdownTimeout        how long {@code close()} waits for borrowed connections to come back
 * @param extraFatalSqlStates    additional SQLStates that mark a connection as broken
 * @param listener               receives pool events
 */
public record PoolConfig(
        String poolName,
        ConnectionFactory connectionFactory,
        int maxSize,
        int minIdle,
        boolean fair,
        Duration acquireTimeout,
        Duration validationTimeout,
        Duration validateAfterIdle,
        Duration maxLifetime,
        Duration idleTimeout,
        Duration housekeepingInterval,
        Duration leakDetectionThreshold,
        Duration shutdownTimeout,
        Set<String> extraFatalSqlStates,
        PoolListener listener) {

    /** Validates the combination of settings. */
    public PoolConfig {
        Objects.requireNonNull(poolName, "poolName");
        Objects.requireNonNull(connectionFactory, "connectionFactory (set jdbcUrl, dataSource or connectionFactory)");
        Objects.requireNonNull(listener, "listener");
        extraFatalSqlStates = Set.copyOf(extraFatalSqlStates);
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be at least 1, got " + maxSize);
        }
        if (minIdle < 0 || minIdle > maxSize) {
            throw new IllegalArgumentException("minIdle must be between 0 and maxSize, got " + minIdle);
        }
        requirePositive(acquireTimeout, "acquireTimeout");
        requirePositive(validationTimeout, "validationTimeout");
        requirePositive(housekeepingInterval, "housekeepingInterval");
        requireNotNegative(validateAfterIdle, "validateAfterIdle");
        requireNotNegative(maxLifetime, "maxLifetime");
        requireNotNegative(idleTimeout, "idleTimeout");
        requireNotNegative(leakDetectionThreshold, "leakDetectionThreshold");
        requireNotNegative(shutdownTimeout, "shutdownTimeout");
    }

    private static void requirePositive(Duration d, String name) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got " + d);
        }
    }

    private static void requireNotNegative(Duration d, String name) {
        if (d == null || d.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative, got " + d);
        }
    }

    /**
     * Starts a builder with the defaults documented on each setter.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link PoolConfig}. */
    public static final class Builder {
        private String poolName = "mini-pool";
        private ConnectionFactory connectionFactory;
        private String jdbcUrl;
        private String username;
        private String password;
        private int maxSize = 10;
        private int minIdle = 0;
        private boolean fair = true;
        private Duration acquireTimeout = Duration.ofSeconds(30);
        private Duration validationTimeout = Duration.ofSeconds(5);
        private Duration validateAfterIdle = Duration.ofMillis(500);
        private Duration maxLifetime = Duration.ofMinutes(30);
        private Duration idleTimeout = Duration.ofMinutes(10);
        private Duration housekeepingInterval = Duration.ofSeconds(30);
        private Duration leakDetectionThreshold = Duration.ZERO;
        private Duration shutdownTimeout = Duration.ofSeconds(10);
        private Set<String> extraFatalSqlStates = Set.of();
        private PoolListener listener = PoolListener.NONE;

        private Builder() {
        }

        /** Name used in thread names and messages. Default {@code mini-pool}. */
        public Builder poolName(String poolName) {
            this.poolName = poolName;
            return this;
        }

        /** JDBC URL opened through {@link DriverManager}. The driver must be on the class path. */
        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        /** User for {@link #jdbcUrl(String)}. */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /** Password for {@link #jdbcUrl(String)}. */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /** Uses a driver-provided, non-pooling {@link DataSource} to open connections. */
        public Builder dataSource(DataSource dataSource) {
            Objects.requireNonNull(dataSource, "dataSource");
            this.connectionFactory = dataSource::getConnection;
            return this;
        }

        /** Uses a custom factory to open connections. */
        public Builder connectionFactory(ConnectionFactory connectionFactory) {
            this.connectionFactory = connectionFactory;
            return this;
        }

        /** Upper bound on physical connections. Default 10. */
        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        /** Idle connections kept ready by the housekeeper. Default 0. */
        public Builder minIdle(int minIdle) {
            this.minIdle = minIdle;
            return this;
        }

        /**
         * Whether waiting threads are served strictly in arrival order. Default {@code true}.
         * Unfair mode lets a thread that is already running take a freed connection ahead of
         * parked waiters, which gives higher throughput under contention at the cost of
         * unbounded worst-case wait for an individual thread.
         */
        public Builder fair(boolean fair) {
            this.fair = fair;
            return this;
        }

        /** Longest {@code getConnection()} blocks before failing. Default 30 s. */
        public Builder acquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = acquireTimeout;
            return this;
        }

        /** Budget for the liveness check on borrow. Default 5 s. */
        public Builder validationTimeout(Duration validationTimeout) {
            this.validationTimeout = validationTimeout;
            return this;
        }

        /**
         * Connections that were returned less than this long ago are handed out without an
         * {@code isValid} round trip. Default 500 ms; zero validates on every borrow.
         */
        public Builder validateAfterIdle(Duration validateAfterIdle) {
            this.validateAfterIdle = validateAfterIdle;
            return this;
        }

        /** Retire connections after this age. Default 30 min; zero disables. */
        public Builder maxLifetime(Duration maxLifetime) {
            this.maxLifetime = maxLifetime;
            return this;
        }

        /** Close idle connections above {@code minIdle} after this long. Default 10 min; zero disables. */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        /** Period of the eviction and top-up task. Default 30 s. */
        public Builder housekeepingInterval(Duration housekeepingInterval) {
            this.housekeepingInterval = housekeepingInterval;
            return this;
        }

        /** Report connections held longer than this. Default zero (off). */
        public Builder leakDetectionThreshold(Duration leakDetectionThreshold) {
            this.leakDetectionThreshold = leakDetectionThreshold;
            return this;
        }

        /** How long {@code close()} waits for borrowed connections before aborting them. Default 10 s. */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        /** Extra SQLStates, beyond the built-in list, after which a connection is discarded. */
        public Builder extraFatalSqlStates(Set<String> states) {
            this.extraFatalSqlStates = states;
            return this;
        }

        /** Receives leak reports and connection open/close events. */
        public Builder listener(PoolListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * Validates and freezes the configuration.
         *
         * @return the immutable configuration
         * @throws IllegalArgumentException if a setting is out of range
         */
        public PoolConfig build() {
            ConnectionFactory factory = connectionFactory;
            if (factory == null && jdbcUrl != null) {
                String url = jdbcUrl;
                String user = username;
                String pass = password;
                factory = () -> DriverManager.getConnection(url, user, pass);
            }
            return new PoolConfig(poolName, factory, maxSize, minIdle, fair, acquireTimeout,
                    validationTimeout, validateAfterIdle, maxLifetime, idleTimeout,
                    housekeepingInterval, leakDetectionThreshold, shutdownTimeout,
                    extraFatalSqlStates, listener);
        }
    }
}
