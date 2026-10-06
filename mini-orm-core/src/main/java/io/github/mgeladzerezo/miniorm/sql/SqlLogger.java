package io.github.mgeladzerezo.miniorm.sql;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Logs each statement with its bound parameters and timing, and flags statements slower than
 * a threshold.
 *
 * <p>By default lines go to the {@link System.Logger} named {@code io.github.mgeladzerezo.miniorm.sql}
 * at {@code DEBUG}, and slow statements at {@code WARNING}. A custom sink (for example
 * {@code System.out::println}) receives every line regardless of level.
 */
public final class SqlLogger implements SqlListener {

    private static final System.Logger LOG = System.getLogger("io.github.mgeladzerezo.miniorm.sql");

    private final Consumer<String> sink;
    private final long slowThresholdNanos;

    /**
     * Logs through {@link System.Logger}.
     *
     * @param slowThreshold statements at least this slow are reported as slow; zero disables
     */
    public SqlLogger(Duration slowThreshold) {
        this(null, slowThreshold);
    }

    /**
     * Logs to a custom sink.
     *
     * @param sink          receives one line per statement; {@code null} means {@link System.Logger}
     * @param slowThreshold statements at least this slow are reported as slow; zero disables
     */
    public SqlLogger(Consumer<String> sink, Duration slowThreshold) {
        this.sink = sink;
        this.slowThresholdNanos = Objects.requireNonNull(slowThreshold, "slowThreshold").toNanos();
    }

    @Override
    public void onStatement(SqlEvent event) {
        boolean slow = isSlow(event);
        if (sink != null) {
            sink.accept(format(event, slow));
        } else if (slow) {
            LOG.log(Level.WARNING, () -> format(event, true));
        } else {
            LOG.log(Level.DEBUG, () -> format(event, false));
        }
    }

    /**
     * @param event a statement execution
     * @return whether it reached the slow-query threshold
     */
    public boolean isSlow(SqlEvent event) {
        return slowThresholdNanos > 0 && event.durationNanos() >= slowThresholdNanos;
    }

    private String format(SqlEvent event, boolean slow) {
        return slow
                ? "SLOW QUERY (>= " + slowThresholdNanos / 1_000_000 + " ms) " + event.describe()
                : event.describe();
    }
}
