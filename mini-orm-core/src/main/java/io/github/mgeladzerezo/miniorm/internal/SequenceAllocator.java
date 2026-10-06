package io.github.mgeladzerezo.miniorm.internal;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Hands out ids from a database sequence in blocks. The sequence is created with
 * {@code increment by allocationSize}, so one {@code nextval} reserves the range
 * {@code [value, value + allocationSize)} for this JVM and no other session can receive an id
 * from it. Ids are therefore unique but not gap-free: a block is abandoned on restart.
 *
 * <p>Shared by all sessions of a factory, hence the lock.
 */
public final class SequenceAllocator {

    private final int allocationSize;
    private final ReentrantLock lock = new ReentrantLock();
    private long next;
    private long end;

    public SequenceAllocator(int allocationSize) {
        this.allocationSize = allocationSize;
    }

    /**
     * Returns the next id, calling {@code fetchNextValue} (one database round trip) only when
     * the current block is used up.
     */
    public long next(LongSupplier fetchNextValue) {
        lock.lock();
        try {
            if (next >= end) {
                next = fetchNextValue.getAsLong();
                end = next + allocationSize;
            }
            return next++;
        } finally {
            lock.unlock();
        }
    }
}
