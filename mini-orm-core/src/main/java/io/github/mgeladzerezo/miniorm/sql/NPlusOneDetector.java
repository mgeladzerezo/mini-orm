package io.github.mgeladzerezo.miniorm.sql;

import io.github.mgeladzerezo.miniorm.sql.SqlEvent.Kind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Detects the N+1 select pattern: the same SELECT text executed over and over with different
 * parameters, which is what lazy loading inside a loop produces.
 *
 * <p>The detector counts executions per distinct SQL string. Because every value is a bind
 * parameter, the N lazy loads of an N+1 share one string, while an {@code IN (...)} batch
 * fetch or a join is a single execution. A statement that repeats at least {@code threshold}
 * times is reported.
 *
 * <pre>{@code
 * NPlusOneDetector detector = new NPlusOneDetector(3);
 * factory.addListener(detector);
 * renderOrderList(session);
 * detector.assertNone();   // fails if the page lazily loaded each order's customer
 * }</pre>
 */
public final class NPlusOneDetector implements SqlListener {

    private final int threshold;
    private final Map<String, Integer> selects = new LinkedHashMap<>();

    /**
     * @param threshold how many executions of one SELECT count as an N+1; at least 2
     */
    public NPlusOneDetector(int threshold) {
        if (threshold < 2) {
            throw new IllegalArgumentException("threshold must be at least 2, got " + threshold);
        }
        this.threshold = threshold;
    }

    @Override
    public synchronized void onStatement(SqlEvent event) {
        if (event.kind() == Kind.SELECT) {
            selects.merge(event.sql(), 1, Integer::sum);
        }
    }

    /** Forgets everything recorded so far. */
    public synchronized void reset() {
        selects.clear();
    }

    /**
     * @return the SELECT statements that reached the threshold, with their execution counts
     */
    public synchronized Map<String, Integer> repeated() {
        return selects.entrySet().stream().filter(e -> e.getValue() >= threshold)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Fails if any SELECT was repeated at least {@code threshold} times.
     *
     * @throws AssertionError naming each repeated statement and how often it ran
     */
    public void assertNone() {
        Map<String, Integer> offenders = repeated();
        if (!offenders.isEmpty()) {
            throw new AssertionError("N+1 select detected. Statements executed " + threshold
                    + " or more times (use fetch(...) on the query to load the association up front):\n  "
                    + offenders.entrySet().stream().map(e -> e.getValue() + "x " + e.getKey())
                    .collect(Collectors.joining("\n  ")));
        }
    }
}
