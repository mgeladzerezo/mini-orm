package io.github.mgeladzerezo.miniorm.sql;

import io.github.mgeladzerezo.miniorm.sql.SqlEvent.Kind;
import java.util.ArrayList;
import java.util.List;

/**
 * Counts and records the statements executed while it is registered. Intended for tests that
 * pin down how many round trips an operation costs:
 *
 * <pre>{@code
 * QueryCounter counter = new QueryCounter();
 * factory.addListener(counter);
 * ... load 50 orders with their customers ...
 * counter.assertCount(Kind.SELECT, 2);
 * }</pre>
 */
public final class QueryCounter implements SqlListener {

    private final List<SqlEvent> events = new ArrayList<>();

    /** Creates an empty counter. */
    public QueryCounter() {
    }

    @Override
    public synchronized void onStatement(SqlEvent event) {
        events.add(event);
    }

    /** Forgets everything recorded so far. */
    public synchronized void reset() {
        events.clear();
    }

    /**
     * @return number of round trips recorded
     */
    public synchronized int count() {
        return events.size();
    }

    /**
     * @param kind statement category
     * @return number of round trips of that category
     */
    public synchronized int count(Kind kind) {
        return (int) events.stream().filter(e -> e.kind() == kind).count();
    }

    /**
     * @return the recorded events in execution order
     */
    public synchronized List<SqlEvent> events() {
        return List.copyOf(events);
    }

    /**
     * @return the SQL text of each recorded statement in execution order
     */
    public synchronized List<String> statements() {
        return events.stream().map(SqlEvent::sql).toList();
    }

    /**
     * Fails unless exactly {@code expected} statements of the given kind were executed.
     *
     * @param kind     statement category
     * @param expected expected number of round trips
     * @throws AssertionError listing the statements that were executed
     */
    public synchronized void assertCount(Kind kind, int expected) {
        int actual = count(kind);
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " " + kind + " statement(s) but " + actual
                    + " were executed:\n  " + String.join("\n  ", events.stream()
                    .filter(e -> e.kind() == kind).map(SqlEvent::describe).toList()));
        }
    }
}
