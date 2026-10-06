package io.github.mgeladzerezo.miniorm.pool;

import io.github.mgeladzerezo.miniorm.pool.PoolEntry.Lease;
import java.io.PrintWriter;
import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A bounded JDBC connection pool.
 *
 * <h2>How it stays correct</h2>
 * Three structures, each with one job:
 * <ul>
 *   <li>a {@link Semaphore} with {@code maxSize} permits. Holding a permit is the right to hold
 *       one connection. It is what makes acquisition blocking, bounded, fair (FIFO) and
 *       time-limited, and it is the only thing a borrower ever waits on;</li>
 *   <li>a concurrent deque of idle entries, used LIFO so that the most recently used connection
 *       is reused first and the rest can age out. Removing an entry from the deque is atomic,
 *       and whoever removes it owns it: that single rule is why a connection cannot reach two
 *       threads;</li>
 *   <li>an atomic counter of physical connections that is raised by compare-and-set
 *       <em>before</em> a connection is opened, so borrowers and the housekeeper together can
 *       never open more than {@code maxSize}.</li>
 * </ul>
 * Every path out of {@link #getConnection()} that does not hand a connection to the caller
 * (timeout, interrupt, failed validation, failed creation, pool closed) releases its permit in
 * one {@code catch} block, and every borrowed connection releases its permit in a
 * {@code finally} when it is returned. Permits therefore cannot leak, which the test suite
 * checks after each failure scenario by borrowing {@code maxSize} connections at once.
 *
 * <p>A daemon housekeeping thread retires connections past their max lifetime, trims idle
 * connections down to {@code minIdle}, tops the pool back up to {@code minIdle}, and scans
 * for leaks.
 */
public final class MiniPool implements DataSource, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MiniPool.class.getName());

    /** Released on shutdown so that every thread parked in {@code getConnection()} wakes up. */
    private static final int WAKE_ALL_PERMITS = 1 << 20;
    private static final long CREATE_BACKOFF_START_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long CREATE_BACKOFF_MAX_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final PoolConfig config;
    private final String name;
    private final int maxSize;
    private final long acquireTimeoutNanos;
    private final long validateAfterIdleNanos;
    private final long maxLifetimeNanos;
    private final long idleTimeoutNanos;
    private final long leakThresholdNanos;

    private final Semaphore permits;
    private final ConcurrentLinkedDeque<PoolEntry> idle = new ConcurrentLinkedDeque<>();
    /** Every live entry, idle or borrowed. Membership is the guard against double-destroy. */
    private final Set<PoolEntry> entries = ConcurrentHashMap.newKeySet();
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor housekeeper;

    private final AcquireHistogram acquireTime = new AcquireHistogram();
    private final LongAdder acquired = new LongAdder();
    private final LongAdder acquireTimeouts = new LongAdder();
    private final LongAdder created = new LongAdder();
    private final LongAdder leaks = new LongAdder();
    private final Map<EvictionReason, LongAdder> closedByReason = new EnumMap<>(EvictionReason.class);

    /**
     * Creates the pool and starts its housekeeping thread. No connection is opened on the
     * calling thread; if {@code minIdle > 0} the housekeeper starts filling immediately.
     *
     * @param config pool settings
     */
    @SuppressWarnings("this-escape") // every field is assigned before the tasks are scheduled
    public MiniPool(PoolConfig config) {
        this.config = config;
        this.name = config.poolName();
        this.maxSize = config.maxSize();
        this.acquireTimeoutNanos = config.acquireTimeout().toNanos();
        this.validateAfterIdleNanos = config.validateAfterIdle().toNanos();
        this.maxLifetimeNanos = config.maxLifetime().toNanos();
        this.idleTimeoutNanos = config.idleTimeout().toNanos();
        this.leakThresholdNanos = config.leakDetectionThreshold().toNanos();
        this.permits = new Semaphore(maxSize, config.fair());
        for (EvictionReason reason : EvictionReason.values()) {
            closedByReason.put(reason, new LongAdder());
        }
        this.housekeeper = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, name + "-housekeeper");
            thread.setDaemon(true);
            return thread;
        });
        housekeeper.setRemoveOnCancelPolicy(true);
        long period = config.housekeepingInterval().toNanos();
        housekeeper.scheduleWithFixedDelay(this::houseKeep, 0, period, TimeUnit.NANOSECONDS);
        if (leakThresholdNanos > 0) {
            long scan = Math.max(TimeUnit.MILLISECONDS.toNanos(1), Math.min(period, leakThresholdNanos / 2));
            housekeeper.scheduleWithFixedDelay(this::scanForLeaks, scan, scan, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Convenience factory with default settings.
     *
     * @param jdbcUrl  JDBC URL
     * @param username database user
     * @param password database password
     * @return a running pool
     */
    public static MiniPool create(String jdbcUrl, String username, String password) {
        return new MiniPool(PoolConfig.builder().jdbcUrl(jdbcUrl).username(username).password(password).build());
    }

    // ------------------------------------------------------------------ borrow

    /**
     * Borrows a connection, blocking for at most {@link PoolConfig#acquireTimeout()}.
     *
     * @return a connection whose {@code close()} returns it to the pool
     * @throws SQLTransientConnectionException if no connection became available in time
     * @throws SQLException                    if the pool is closed or the thread was interrupted
     */
    @Override
    public Connection getConnection() throws SQLException {
        if (closed.get()) {
            throw closedException();
        }
        long start = System.nanoTime();
        long deadline = start + acquireTimeoutNanos;
        boolean gotPermit;
        try {
            gotPermit = permits.tryAcquire(acquireTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interruptedException(e);
        }
        if (!gotPermit) {
            acquireTimeouts.increment();
            throw timeoutException(start, null);
        }
        try {
            if (closed.get()) {
                throw closedException();
            }
            PoolEntry entry = obtainEntry(start, deadline);
            long now = System.nanoTime();
            Throwable trace = leakThresholdNanos > 0 ? new Throwable("Connection borrowed here") : null;
            entry.lease = new Lease(Thread.currentThread(), now, trace);
            acquired.increment();
            acquireTime.record(now - start);
            return new PooledConnection(this, entry);
        } catch (Throwable t) {
            // No connection reached the caller, so the permit must not stay taken.
            permits.release();
            throw t;
        }
    }

    /**
     * Finds or opens a connection for a thread that already holds a permit. Any entry taken
     * from the idle deque and found unusable is destroyed here and the search continues, so a
     * failed validation costs the caller time but never a permit.
     */
    private PoolEntry obtainEntry(long start, long deadline) throws SQLException {
        SQLException lastFailure = null;
        long backoff = CREATE_BACKOFF_START_NANOS;
        while (true) {
            PoolEntry entry = idle.pollFirst();
            long now = System.nanoTime();
            if (entry != null) {
                if (entry.isExpired(now)) {
                    destroy(entry, EvictionReason.MAX_LIFETIME);
                } else if (now - entry.lastReturnedNanos >= validateAfterIdleNanos && !isAlive(entry, deadline - now)) {
                    destroy(entry, EvictionReason.VALIDATION_FAILED);
                } else {
                    return entry;
                }
                continue;
            }
            long pause;
            if (reserveSlot()) {
                try {
                    return createEntry();
                } catch (SQLException e) {
                    total.decrementAndGet();
                    lastFailure = e;
                }
                // The database may be restarting: retry with backoff until the deadline.
                pause = backoff;
                backoff = Math.min(backoff * 2, CREATE_BACKOFF_MAX_NANOS);
            } else {
                // Every slot is taken although we hold a permit: the housekeeper is opening an
                // idle connection right now. It will appear in the deque momentarily.
                pause = TimeUnit.MICROSECONDS.toNanos(50);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                acquireTimeouts.increment();
                throw timeoutException(start, lastFailure);
            }
            if (closed.get()) {
                throw closedException();
            }
            LockSupport.parkNanos(this, Math.min(pause, remaining));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw interruptedException(null);
            }
        }
    }

    private boolean isAlive(PoolEntry entry, long remainingNanos) {
        long budget = Math.min(config.validationTimeout().toNanos(), Math.max(remainingNanos, 0));
        int seconds = (int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(budget));
        try {
            return entry.physical.isValid(seconds);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Claims the right to open one more physical connection, or reports that the pool is full. */
    private boolean reserveSlot() {
        while (true) {
            int current = total.get();
            if (current >= maxSize) {
                return false;
            }
            if (total.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Opens a connection for a slot the caller has reserved. The caller un-reserves on failure. */
    private PoolEntry createEntry() throws SQLException {
        Connection physical = config.connectionFactory().create();
        if (physical == null) {
            throw new SQLException(name + ": connection factory returned null");
        }
        PoolEntry entry;
        try {
            long lifetime = maxLifetimeNanos;
            if (lifetime > 0) {
                // Up to 2.5% shorter per connection so they do not all expire in the same tick.
                lifetime -= ThreadLocalRandom.current().nextLong(lifetime / 40 + 1);
            }
            entry = new PoolEntry(physical, System.nanoTime(), lifetime);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(physical);
            throw e;
        }
        entries.add(entry);
        created.increment();
        notifyListener(() -> config.listener().onConnectionCreated());
        return entry;
    }

    // ------------------------------------------------------------------ return

    /**
     * Called by {@link PooledConnection#close()} exactly once per borrow.
     *
     * @param failure non-null if the connection must not be reused
     */
    void release(PoolEntry entry, EvictionReason failure) {
        try {
            Lease lease = entry.lease;
            entry.lease = null;
            long now = System.nanoTime();
            if (lease != null && lease.leakReported) {
                LOG.log(Level.INFO, "{0}: connection previously reported as leaked was returned after {1} ms by {2}",
                        name, TimeUnit.NANOSECONDS.toMillis(now - lease.sinceNanos), lease.thread.getName());
            }
            if (failure != null) {
                destroy(entry, failure);
            } else if (closed.get()) {
                destroy(entry, EvictionReason.POOL_CLOSED);
            } else if (entry.isExpired(now)) {
                destroy(entry, EvictionReason.MAX_LIFETIME);
            } else {
                entry.lastReturnedNanos = now;
                idle.addFirst(entry);
                // close() may have drained the deque between the check above and the add.
                if (closed.get() && idle.remove(entry)) {
                    destroy(entry, EvictionReason.POOL_CLOSED);
                }
            }
        } finally {
            permits.release();
        }
    }

    /**
     * Closes a physical connection and frees its slot. Safe to call twice for the same entry;
     * only the first call has an effect.
     *
     * <p>The slot is freed before the socket is closed. Closing a dead connection can be slow,
     * and making borrowers wait for it would turn one broken connection into a stall, so for
     * the duration of that close the database may briefly see one connection more than
     * {@code maxSize}.
     */
    private void destroy(PoolEntry entry, EvictionReason reason) {
        if (!entries.remove(entry)) {
            return;
        }
        total.decrementAndGet();
        closedByReason.get(reason).increment();
        closeQuietly(entry.physical);
        notifyListener(() -> config.listener().onConnectionClosed(reason));
    }

    private void closeQuietly(Connection physical) {
        try {
            physical.close();
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.DEBUG, () -> name + ": error closing a physical connection: " + e);
        }
    }

    // ------------------------------------------------------------------ housekeeping

    private void houseKeep() {
        try {
            evictIdle();
            topUpIdle();
        } catch (RuntimeException e) {
            // A periodic task that throws is silently cancelled by the executor; never let it.
            LOG.log(Level.WARNING, name + ": housekeeping failed", e);
        }
    }

    private void evictIdle() {
        long now = System.nanoTime();
        int idleCount = idle.size();
        // Oldest first: the deque is used LIFO, so the tail holds the connections used least recently.
        for (Iterator<PoolEntry> it = idle.descendingIterator(); it.hasNext(); ) {
            PoolEntry entry = it.next();
            EvictionReason reason = null;
            if (entry.isExpired(now)) {
                reason = EvictionReason.MAX_LIFETIME;
            } else if (idleTimeoutNanos > 0 && idleCount > config.minIdle()
                    && now - entry.lastReturnedNanos >= idleTimeoutNanos) {
                reason = EvictionReason.IDLE_TIMEOUT;
            }
            // remove() succeeds only if no borrower polled the entry in the meantime.
            if (reason != null && idle.remove(entry)) {
                destroy(entry, reason);
                idleCount--;
            }
        }
    }

    private void topUpIdle() {
        while (!closed.get() && idle.size() < config.minIdle() && reserveSlot()) {
            PoolEntry entry;
            try {
                entry = createEntry();
            } catch (SQLException | RuntimeException e) {
                total.decrementAndGet();
                LOG.log(Level.DEBUG, () -> name + ": could not open an idle connection: " + e);
                return;
            }
            idle.addLast(entry);
            if (closed.get() && idle.remove(entry)) {
                destroy(entry, EvictionReason.POOL_CLOSED);
            }
        }
    }

    private void scanForLeaks() {
        try {
            long now = System.nanoTime();
            for (PoolEntry entry : entries) {
                Lease lease = entry.lease;
                if (lease == null || lease.leakReported || now - lease.sinceNanos < leakThresholdNanos) {
                    continue;
                }
                lease.leakReported = true;
                leaks.increment();
                Duration held = Duration.ofNanos(now - lease.sinceNanos);
                LOG.log(Level.WARNING, name + ": possible connection leak, held for " + held.toMillis()
                        + " ms by thread " + lease.thread.getName() + "; borrowed at:", lease.trace);
                PoolListener.Leak leak = new PoolListener.Leak(name, lease.thread.getName(), held, lease.trace);
                notifyListener(() -> config.listener().onLeakDetected(leak));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, name + ": leak scan failed", e);
        }
    }

    private void notifyListener(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, name + ": pool listener threw", e);
        }
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Shuts the pool down in order: refuse new borrowers, stop the housekeeper, close idle
     * connections, wake blocked waiters (they fail with "pool is closed"), wait up to
     * {@link PoolConfig#shutdownTimeout()} for borrowed connections to be returned (each is
     * closed as it comes back), then abort whatever is still out. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        housekeeper.shutdownNow();
        drainIdle();
        permits.release(WAKE_ALL_PERMITS);

        long deadline = System.nanoTime() + config.shutdownTimeout().toNanos();
        boolean interrupted = false;
        while (!entries.isEmpty() && System.nanoTime() < deadline && !interrupted) {
            drainIdle();
            LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(1));
            interrupted = Thread.interrupted();
        }
        for (PoolEntry entry : entries) {
            Lease lease = entry.lease;
            if (lease != null) {
                LOG.log(Level.WARNING, "{0}: aborting a connection still held by {1} at shutdown",
                        name, lease.thread.getName());
            }
            idle.remove(entry);
            destroy(entry, EvictionReason.POOL_CLOSED);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainIdle() {
        PoolEntry entry;
        while ((entry = idle.pollFirst()) != null) {
            destroy(entry, EvictionReason.POOL_CLOSED);
        }
    }

    /**
     * Whether {@link #close()} has been called.
     *
     * @return {@code true} once shutdown has started
     */
    public boolean isClosed() {
        return closed.get();
    }

    // ------------------------------------------------------------------ introspection

    /**
     * Takes a snapshot of gauges, counters and the acquire-time histogram.
     *
     * @return current metrics
     */
    public PoolMetrics metrics() {
        int active = 0;
        for (PoolEntry entry : entries) {
            if (entry.lease != null) {
                active++;
            }
        }
        Map<EvictionReason, Long> closedCounts = new EnumMap<>(EvictionReason.class);
        closedByReason.forEach((reason, count) -> closedCounts.put(reason, count.sum()));
        return new PoolMetrics(name, active, idle.size(), total.get(), permits.getQueueLength(), maxSize,
                acquired.sum(), acquireTimeouts.sum(), created.sum(), Map.copyOf(closedCounts), leaks.sum(),
                acquireTime.snapshot());
    }

    /**
     * The configuration this pool was built with.
     *
     * @return the immutable settings
     */
    public PoolConfig config() {
        return config;
    }

    /** Free permits; equals {@code maxSize} whenever nothing is borrowed. For tests. */
    int availablePermits() {
        return permits.availablePermits();
    }

    private SQLException closedException() {
        return new SQLException(name + " is closed", "08003");
    }

    private SQLException interruptedException(InterruptedException cause) {
        return new SQLException(name + ": interrupted while waiting for a connection", "08000", cause);
    }

    private SQLTransientConnectionException timeoutException(long start, SQLException cause) {
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        PoolMetrics m = metrics();
        String detail = cause == null ? "" : "; last connection attempt failed: " + cause.getMessage();
        return new SQLTransientConnectionException(name + ": no connection available after " + waited
                + " ms (active=" + m.active() + ", idle=" + m.idle() + ", waiting=" + m.waiting()
                + ", max=" + maxSize + ")" + detail, "08001", cause);
    }

    // ------------------------------------------------------------------ DataSource plumbing

    /** Not supported: credentials belong to the pool configuration. */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("Per-call credentials are not supported; configure them on the pool");
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // The pool logs through System.Logger.
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("Use PoolConfig.acquireTimeout");
    }

    @Override
    public int getLoginTimeout() {
        return (int) config.acquireTimeout().toSeconds();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("The pool logs through System.Logger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException(name + " is not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public String toString() {
        PoolMetrics m = metrics();
        return "MiniPool[" + name + ", active=" + m.active() + ", idle=" + m.idle() + ", total=" + m.total()
                + ", waiting=" + m.waiting() + ", max=" + maxSize + (closed.get() ? ", closed" : "") + "]";
    }
}
