package io.github.mgeladzerezo.miniorm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.miniorm.error.OptimisticLockException;
import io.github.mgeladzerezo.miniorm.error.PessimisticLockException;
import io.github.mgeladzerezo.miniorm.model.Customer;
import io.github.mgeladzerezo.miniorm.support.OrmTest;
import io.github.mgeladzerezo.miniorm.support.TestDb;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Optimistic locking with {@code @Version} and pessimistic locking with {@code FOR UPDATE},
 * against real concurrent transactions on both databases.
 */
@Timeout(120)
class OptimisticLockingTest extends OrmTest {

    private Long persist(String email) {
        return db.orm().inTransaction(session -> {
            Customer customer = TestDb.customer("Ada", email);
            session.persist(customer);
            return customer;
        }).getId();
    }

    @Test
    void theSecondWriterOfAStaleVersionGetsATypedExceptionAndChangesNothing() {
        Long id = persist("ada@x.io");
        try (Session first = db.orm().openSession(); Session second = db.orm().openSession()) {
            Customer a = first.find(Customer.class, id);
            Customer b = second.find(Customer.class, id);

            first.runInTransaction(tx -> a.setName("first writer"));

            assertThatThrownBy(() -> second.runInTransaction(tx -> b.setName("second writer")))
                    .isInstanceOfSatisfying(OptimisticLockException.class, e -> {
                        assertThat(e.entityClass()).isEqualTo(Customer.class);
                        assertThat(e.id()).isEqualTo(id);
                        assertThat(e.expectedVersion()).isEqualTo(0L);
                    });
        }
        db.orm().runInSession(session -> {
            Customer stored = session.find(Customer.class, id);
            assertThat(stored.getName()).isEqualTo("first writer");
            assertThat(stored.getVersion()).isEqualTo(1);
        });
    }

    @Test
    void deletingAStaleVersionFails() {
        Long id = persist("ada@x.io");
        try (Session first = db.orm().openSession(); Session second = db.orm().openSession()) {
            Customer a = first.find(Customer.class, id);
            Customer b = second.find(Customer.class, id);
            first.runInTransaction(tx -> a.setName("changed"));
            assertThatThrownBy(() -> second.runInTransaction(tx -> second.remove(b)))
                    .isInstanceOf(OptimisticLockException.class);
        }
        db.orm().runInSession(session -> assertThat(session.find(Customer.class, id)).isNotNull());
    }

    @Test
    void updatingARowThatWasDeletedByAnotherTransactionFails() {
        Long id = persist("ada@x.io");
        try (Session first = db.orm().openSession(); Session second = db.orm().openSession()) {
            Customer a = first.find(Customer.class, id);
            Customer b = second.find(Customer.class, id);
            second.runInTransaction(tx -> second.remove(b));
            assertThatThrownBy(() -> first.runInTransaction(tx -> a.setName("too late")))
                    .isInstanceOf(OptimisticLockException.class);
        }
    }

    @Test
    void mergeRejectsADetachedCopyThatIsOlderThanTheRow() {
        Long id = persist("ada@x.io");
        Customer stale = db.orm().inSession(session -> session.find(Customer.class, id));
        db.orm().runInTransaction(session -> session.find(Customer.class, id).setName("newer"));

        stale.setName("edited from a stale copy");
        assertThatThrownBy(() -> db.orm().runInTransaction(session -> session.merge(stale)))
                .isInstanceOf(OptimisticLockException.class);
        db.orm().runInSession(session ->
                assertThat(session.find(Customer.class, id).getName()).isEqualTo("newer"));
    }

    /**
     * The headline claim: concurrent read-modify-write cycles with retry never lose an
     * increment. Without the version check, two sessions that read the same points value would
     * both write value+1 and the final total would fall short.
     */
    @Test
    void concurrentIncrementsWithRetryLoseNoUpdate() throws Exception {
        Long id = persist("counter@x.io");
        int threads = 6;
        int incrementsPerThread = 15;
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(executor.submit(() -> {
                start.await();
                for (int i = 0; i < incrementsPerThread; i++) {
                    while (true) {
                        try {
                            db.orm().runInTransaction(session -> {
                                Customer customer = session.find(Customer.class, id);
                                customer.setPoints(customer.getPoints() + 1);
                            });
                            break;
                        } catch (OptimisticLockException conflict) {
                            conflicts.incrementAndGet();
                        }
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(90, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        db.orm().runInSession(session -> {
            Customer stored = session.find(Customer.class, id);
            assertThat(stored.getPoints()).isEqualTo(threads * incrementsPerThread);
            assertThat(stored.getVersion()).as("one version bump per successful update")
                    .isEqualTo(threads * incrementsPerThread);
        });
        assertThat(conflicts.get()).as("the test only means something if writers really collided").isPositive();
    }

    // ------------------------------------------------------------------ pessimistic

    @Test
    void forUpdateMakesAConcurrentLockerWaitUntilTheFirstTransactionEnds() throws Exception {
        Long id = persist("ada@x.io");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger();
        AtomicInteger firstDone = new AtomicInteger();
        AtomicInteger secondAcquired = new AtomicInteger();
        try (Session holder = db.orm().openSession()) {
            Future<?> first = executor.submit(() -> {
                holder.runInTransaction(tx -> {
                    holder.find(Customer.class, id, LockMode.FOR_UPDATE);
                    holding.countDown();
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    firstDone.set(order.incrementAndGet());
                });
                return null;
            });
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            ExecutorService second = Executors.newSingleThreadExecutor();
            Future<?> waiter = second.submit(() -> {
                db.orm().runInTransaction(session -> {
                    session.find(Customer.class, id, LockMode.FOR_UPDATE);
                    secondAcquired.set(order.incrementAndGet());
                });
                return null;
            });
            // The second locker must still be blocked after the first has held the lock for a while.
            Thread.sleep(800);
            assertThat(waiter.isDone()).as("the second FOR UPDATE must wait").isFalse();
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
            waiter.get(30, TimeUnit.SECONDS);
            second.shutdownNow();
        }
        executor.shutdownNow();
        assertThat(firstDone.get()).isEqualTo(1);
        assertThat(secondAcquired.get()).as("the waiter got the lock only after the holder finished").isEqualTo(2);
    }

    @Test
    void forUpdateNowaitFailsImmediatelyWithATypedException() throws Exception {
        Long id = persist("ada@x.io");
        try (Session holder = db.orm().openSession()) {
            holder.runInTransaction(tx -> {
                holder.find(Customer.class, id, LockMode.FOR_UPDATE);
                try (Session other = db.orm().openSession()) {
                    long started = System.nanoTime();
                    assertThatThrownBy(() -> other.runInTransaction(
                            t -> other.find(Customer.class, id, LockMode.FOR_UPDATE_NOWAIT)))
                            .isInstanceOf(PessimisticLockException.class);
                    assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
                            .as("NOWAIT must not wait for the lock").isLessThan(2);
                }
            });
        }
    }

    @Test
    void aQueryCanLockTheRowsItSelects() {
        persist("ada@x.io");
        db.orm().runInTransaction(session -> {
            List<Customer> locked = session.from(Customer.class).lock(LockMode.FOR_UPDATE).list();
            assertThat(locked).hasSize(1);
            assertThat(db.counter().statements().getLast()).endsWith("for update");
        });
    }
}
