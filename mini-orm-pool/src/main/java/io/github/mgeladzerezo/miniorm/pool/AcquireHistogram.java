package io.github.mgeladzerezo.miniorm.pool;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free histogram of {@code getConnection()} latencies.
 *
 * <p>Buckets are powers of two starting at one microsecond: bucket {@code i} counts samples
 * {@code <= 2^i} microseconds, and the last bucket is unbounded. That is 27 counters for a
 * range of 1 microsecond to about 33 seconds, which is coarse (a percentile is only known to
 * within a factor of two) but costs one {@link LongAdder} increment per acquisition and never
 * allocates.
 */
final class AcquireHistogram {

    static final int BUCKETS = 27;
    private static final long MICRO = 1_000L;

    private final LongAdder[] counts = new LongAdder[BUCKETS];
    private final LongAdder totalNanos = new LongAdder();
    private final LongAccumulator maxNanos = new LongAccumulator(Math::max, 0);

    AcquireHistogram() {
        for (int i = 0; i < BUCKETS; i++) {
            counts[i] = new LongAdder();
        }
    }

    void record(long nanos) {
        counts[bucketFor(nanos)].increment();
        totalNanos.add(nanos);
        maxNanos.accumulate(nanos);
    }

    /** Index of the first bucket whose upper bound is {@code >= nanos}. */
    static int bucketFor(long nanos) {
        long micros = nanos <= 0 ? 0 : (nanos - 1) / MICRO + 1; // ceiling, without overflow
        if (micros <= 1) {
            return 0;
        }
        int index = Long.SIZE - Long.numberOfLeadingZeros(micros - 1);
        return Math.min(index, BUCKETS - 1);
    }

    /** Upper bound of bucket {@code i} in nanoseconds; {@code Long.MAX_VALUE} for the last. */
    static long upperBoundNanos(int i) {
        return i == BUCKETS - 1 ? Long.MAX_VALUE : (1L << i) * MICRO;
    }

    PoolMetrics.Histogram snapshot() {
        long[] bounds = new long[BUCKETS];
        long[] values = new long[BUCKETS];
        long count = 0;
        for (int i = 0; i < BUCKETS; i++) {
            bounds[i] = upperBoundNanos(i);
            values[i] = counts[i].sum();
            count += values[i];
        }
        return new PoolMetrics.Histogram(bounds, values, count, totalNanos.sum(), maxNanos.get());
    }
}
