package io.github.mgeladzerezo.miniorm.sql;

import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * One statement execution as seen by {@link SqlListener}s.
 *
 * @param sql           the SQL text with {@code ?} placeholders, exactly as sent to the driver
 * @param parameterSets the bound values; one list for a plain statement, one per row for a batch
 * @param durationNanos wall-clock time from prepare to the last row read or update count
 * @param rows          rows returned by a query, or rows affected by an update or batch
 * @param failure       the exception if the statement failed, otherwise {@code null}
 */
public record SqlEvent(String sql, List<List<Object>> parameterSets, long durationNanos, long rows,
                       Throwable failure) {

    /** Statement categories, derived from the first keyword. */
    public enum Kind {
        /** A query. */
        SELECT,
        /** An INSERT, including upserts. */
        INSERT,
        /** An UPDATE. */
        UPDATE,
        /** A DELETE. */
        DELETE,
        /** DDL and everything else. */
        OTHER
    }

    /**
     * @return the statement category
     */
    public Kind kind() {
        String head = sql.stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("select") || head.startsWith("with")) {
            return Kind.SELECT;
        } else if (head.startsWith("insert") || head.startsWith("merge")) {
            return Kind.INSERT;
        } else if (head.startsWith("update")) {
            return Kind.UPDATE;
        } else if (head.startsWith("delete")) {
            return Kind.DELETE;
        }
        return Kind.OTHER;
    }

    /**
     * @return number of parameter sets sent in this round trip; 1 for a non-batched statement
     */
    public int batchSize() {
        return Math.max(1, parameterSets.size());
    }

    /**
     * @return the bound values of the first (or only) parameter set
     */
    public List<Object> parameters() {
        return parameterSets.isEmpty() ? List.of() : parameterSets.getFirst();
    }

    /**
     * @return the elapsed time
     */
    public Duration duration() {
        return Duration.ofNanos(durationNanos);
    }

    /**
     * @return whether the statement threw
     */
    public boolean failed() {
        return failure != null;
    }

    /**
     * One log line: timing, SQL, bound parameters and row count. Parameters are printed next
     * to the statement, never spliced into it, so the line shows exactly what was executed.
     *
     * @return a human-readable description
     */
    public String describe() {
        StringBuilder out = new StringBuilder(sql.length() + 64);
        out.append(String.format(Locale.ROOT, "[%.2f ms] ", durationNanos / 1_000_000.0)).append(sql);
        if (parameterSets.size() > 1) {
            out.append(" | batch of ").append(parameterSets.size()).append(": ")
                    .append(parameterSets.stream().limit(3).map(SqlEvent::format).collect(Collectors.joining(", ")));
            if (parameterSets.size() > 3) {
                out.append(", ...");
            }
        } else if (!parameters().isEmpty()) {
            out.append(" | params=").append(format(parameters()));
        }
        out.append(failed() ? " | FAILED: " + failure.getMessage() : " | rows=" + rows);
        return out.toString();
    }

    private static String format(List<Object> parameters) {
        return parameters.stream().map(SqlEvent::format).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String format(Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> "'" + (s.length() > 80 ? s.substring(0, 77) + "..." : s) + "'";
            case byte[] bytes -> "x'" + HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 16))
                    + (bytes.length > 16 ? "...'" : "'");
            default -> value.toString();
        };
    }
}
