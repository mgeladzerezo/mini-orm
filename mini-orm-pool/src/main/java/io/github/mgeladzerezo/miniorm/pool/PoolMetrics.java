package io.github.mgeladzerezo.miniorm.pool;

import java.util.Map;

/**
 * A point-in-time view of a pool. The gauges are read without stopping the pool, so under load
 * they can be off by the few connections that changed hands while the snapshot was taken.
 *
 * @param poolName           name of the pool
 * @param active             connections currently borrowed
 * @param idle               connections sitting in the pool
 * @param total              physical connections the pool is accounting for
 * @param waiting            threads blocked in {@code getConnection()}
 * @param maxSize            configured upper bound
 * @param acquired           successful {@code getConnection()} calls since start
 * @param acquireTimeouts    {@code getConnection()} calls that gave up
 * @param connectionsCreated physical connections opened since start
 * @param connectionsClosed  physical connections closed since start, by reason
 * @param leaksDetected      leak reports issued since start
 * @param acquireTime        latency distribution of successful acquisitions
 */
public record PoolMetrics(
        String poolName,
        int active,
        int idle,
        int total,
        int waiting,
        int maxSize,
        long acquired,
        long acquireTimeouts,
        long connectionsCreated,
        Map<EvictionReason, Long> connectionsClosed,
        long leaksDetected,
        Histogram acquireTime) {

    /**
     * Latency histogram with power-of-two buckets.
     *
     * @param upperBoundsNanos inclusive upper bound of each bucket; the last is {@code Long.MAX_VALUE}
     * @param counts           samples per bucket
     * @param count            total samples
     * @param totalNanos       sum of all samples
     * @param maxNanos         largest sample seen
     */
    public record Histogram(long[] upperBoundsNanos, long[] counts, long count, long totalNanos, long maxNanos) {

        /**
         * Mean latency.
         *
         * @return mean in nanoseconds, or 0 when there are no samples
         */
        public double meanNanos() {
            return count == 0 ? 0 : (double) totalNanos / count;
        }

        /**
         * Upper bound of the bucket that contains the given percentile. The true value lies
         * between half of the returned bound and the bound itself.
         *
         * @param percentile in the range (0, 100]
         * @return an upper estimate in nanoseconds, or 0 when there are no samples
         */
        public long percentileNanos(double percentile) {
            if (percentile <= 0 || percentile > 100) {
                throw new IllegalArgumentException("percentile must be in (0, 100], got " + percentile);
            }
            if (count == 0) {
                return 0;
            }
            long rank = (long) Math.ceil(count * percentile / 100.0);
            long seen = 0;
            for (int i = 0; i < counts.length; i++) {
                seen += counts[i];
                if (seen >= rank) {
                    // The unbounded last bucket has no meaningful bound; the max is exact.
                    return Math.min(upperBoundsNanos[i], maxNanos);
                }
            }
            return maxNanos;
        }
    }
}
