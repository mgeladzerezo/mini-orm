package io.github.mgeladzerezo.miniorm.pool;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AcquireHistogramTest {

    @Test
    void bucketBoundariesArePowersOfTwoMicroseconds() {
        assertThat(AcquireHistogram.bucketFor(0)).isZero();
        assertThat(AcquireHistogram.bucketFor(1_000)).isZero();
        assertThat(AcquireHistogram.bucketFor(1_001)).isEqualTo(1);
        assertThat(AcquireHistogram.bucketFor(2_000)).isEqualTo(1);
        assertThat(AcquireHistogram.bucketFor(2_001)).isEqualTo(2);
        assertThat(AcquireHistogram.bucketFor(1_000_000)).isEqualTo(10);
        assertThat(AcquireHistogram.bucketFor(Long.MAX_VALUE)).isEqualTo(AcquireHistogram.BUCKETS - 1);
        for (int i = 0; i < AcquireHistogram.BUCKETS - 1; i++) {
            long bound = AcquireHistogram.upperBoundNanos(i);
            assertThat(AcquireHistogram.bucketFor(bound)).isEqualTo(i);
            assertThat(AcquireHistogram.bucketFor(bound + 1)).isEqualTo(i + 1);
        }
    }

    @Test
    void percentilesAndMeanComeFromTheRecordedSamples() {
        AcquireHistogram histogram = new AcquireHistogram();
        for (int i = 0; i < 99; i++) {
            histogram.record(900); // <= 1 us
        }
        histogram.record(3_000_000); // 3 ms, falls in the <= 4.096 ms bucket

        PoolMetrics.Histogram snapshot = histogram.snapshot();

        assertThat(snapshot.count()).isEqualTo(100);
        assertThat(snapshot.maxNanos()).isEqualTo(3_000_000);
        assertThat(snapshot.percentileNanos(50)).isEqualTo(1_000);
        assertThat(snapshot.percentileNanos(99)).isEqualTo(1_000);
        assertThat(snapshot.percentileNanos(100)).isEqualTo(3_000_000);
        assertThat(snapshot.meanNanos()).isEqualTo((99 * 900 + 3_000_000) / 100.0);
        assertThat(new AcquireHistogram().snapshot().percentileNanos(99)).isZero();
    }

    @Test
    void poolRecordsWaitingTimeInTheHistogram() throws Exception {
        FakeDatabase db = new FakeDatabase();
        try (MiniPool pool = new MiniPool(PoolConfig.builder().connectionFactory(db).maxSize(1)
                .acquireTimeout(Duration.ofSeconds(5)).build())) {
            Connection held = pool.getConnection();
            Thread waiter = Thread.ofPlatform().start(() -> {
                try (Connection c = pool.getConnection()) {
                    // got it after the main thread released
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            AcquisitionTest.await(() -> pool.metrics().waiting() == 1);
            AcquisitionTest.sleep(100);
            held.close();
            waiter.join(5_000);

            PoolMetrics.Histogram histogram = pool.metrics().acquireTime();
            assertThat(histogram.count()).isEqualTo(2);
            assertThat(histogram.maxNanos()).as("the waiter's 100 ms wait is visible").isGreaterThan(90_000_000L);
        }
    }
}
