package io.github.mgeladzerezo.miniorm.sql;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Keeps the most recent statements in memory, for showing "what SQL just ran" in a console or
 * an admin page. Bounded, so it can stay registered for the life of the application.
 */
public final class SqlRecorder implements SqlListener {

    private final int capacity;
    private final ArrayDeque<SqlEvent> events;
    private long total;

    /**
     * @param capacity how many statements to remember; the oldest are dropped first
     */
    public SqlRecorder(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        this.events = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void onStatement(SqlEvent event) {
        if (events.size() == capacity) {
            events.removeFirst();
        }
        events.addLast(event);
        total++;
    }

    /**
     * @return the remembered statements, oldest first
     */
    public synchronized List<SqlEvent> recent() {
        return List.copyOf(events);
    }

    /**
     * @return how many statements have been observed since creation, including dropped ones
     */
    public synchronized long total() {
        return total;
    }
}
