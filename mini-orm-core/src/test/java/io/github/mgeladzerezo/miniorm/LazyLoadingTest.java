package io.github.mgeladzerezo.miniorm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.miniorm.error.EntityNotFoundException;
import io.github.mgeladzerezo.miniorm.error.LazyInitializationException;
import io.github.mgeladzerezo.miniorm.model.Customer;
import io.github.mgeladzerezo.miniorm.model.OrderLine;
import io.github.mgeladzerezo.miniorm.model.Product;
import io.github.mgeladzerezo.miniorm.model.PurchaseOrder;
import io.github.mgeladzerezo.miniorm.proxy.EntityProxy;
import io.github.mgeladzerezo.miniorm.query.FetchMode;
import io.github.mgeladzerezo.miniorm.sql.NPlusOneDetector;
import io.github.mgeladzerezo.miniorm.sql.SqlEvent.Kind;
import io.github.mgeladzerezo.miniorm.support.OrmTest;
import io.github.mgeladzerezo.miniorm.support.TestDb;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Lazy {@code @ManyToOne} proxies (generated with {@code java.lang.classfile}), lazy
 * {@code @OneToMany} collections, eager references, fetch plans, and the N+1 detector.
 */
class LazyLoadingTest extends OrmTest {

    /** Five customers with three orders each; every order has one line for one shared product. */
    private void seed() {
        db.orm().runInTransaction(session -> {
            Product product = new Product("P", "Widget", new BigDecimal("3.00"));
            session.persist(product);
            for (int c = 0; c < 5; c++) {
                Customer customer = TestDb.customer("Customer " + c, "c" + c + "@x.io");
                session.persist(customer);
                for (int o = 0; o < 3; o++) {
                    PurchaseOrder order = new PurchaseOrder(customer, BigDecimal.valueOf(10 * c + o));
                    session.persist(order);
                    session.persist(new OrderLine(order, product, o + 1));
                }
            }
        });
        db.counter().reset();
    }

    // ------------------------------------------------------------------ proxies

    @Test
    void aLazyReferenceIsAProxySubclassThatKnowsItsIdWithoutQuerying() {
        seed();
        db.orm().runInSession(session -> {
            PurchaseOrder order = session.from(PurchaseOrder.class).first().orElseThrow();
            db.counter().reset();

            Customer customer = order.getCustomer();
            assertThat(customer).isInstanceOf(EntityProxy.class).isInstanceOf(Customer.class);
            assertThat(MiniOrm.isInitialized(customer)).isFalse();
            assertThat(customer.getId()).isNotNull();
            assertThat(db.counter().count()).as("reading the id of a proxy must not load it").isZero();

            assertThat(customer.getName()).startsWith("Customer");
            assertThat(db.counter().count(Kind.SELECT)).isEqualTo(1);
            assertThat(MiniOrm.isInitialized(customer)).isTrue();
            customer.getEmail();
            assertThat(db.counter().count(Kind.SELECT)).as("loaded once").isEqualTo(1);
        });
    }

    @Test
    void theProxyAndTheLoadedEntityAreTheSameRow() {
        seed();
        db.orm().runInSession(session -> {
            PurchaseOrder order = session.from(PurchaseOrder.class).first().orElseThrow();
            Customer proxy = order.getCustomer();
            Customer real = session.find(Customer.class, proxy.getId());
            assertThat(MiniOrm.unproxy(proxy)).isSameAs(real);
            assertThat(MiniOrm.isInitialized(proxy)).as("find filled the proxy without another query").isTrue();
        });
    }

    @Test
    void changesMadeThroughAProxyAreDirtyCheckedOnTheRealInstance() {
        seed();
        db.orm().runInTransaction(session -> {
            PurchaseOrder order = session.from(PurchaseOrder.class).first().orElseThrow();
            order.getCustomer().setName("renamed via proxy");
        });
        db.orm().runInSession(session -> {
            PurchaseOrder order = session.from(PurchaseOrder.class).first().orElseThrow();
            assertThat(order.getCustomer().getName()).isEqualTo("renamed via proxy");
        });
    }

    @Test
    void aProxyUsedAfterTheSessionClosedFailsWithAClearException() {
        seed();
        PurchaseOrder order = db.orm().inSession(session -> session.from(PurchaseOrder.class).first().orElseThrow());
        assertThat(order.getCustomer().getId()).as("the id needs no session").isNotNull();
        assertThatThrownBy(() -> order.getCustomer().getName())
                .isInstanceOf(LazyInitializationException.class)
                .hasMessageContaining("session").hasMessageContaining("Customer");
    }

    @Test
    void aProxyToAMissingRowFailsWithEntityNotFound() {
        seed();
        db.orm().runInSession(session -> {
            Customer ghost = session.getReference(Customer.class, 987_654L);
            assertThat(ghost).isInstanceOf(EntityProxy.class);
            assertThatThrownBy(ghost::getName).isInstanceOf(EntityNotFoundException.class);
        });
    }

    @Test
    void getReferenceLetsYouSetAForeignKeyWithoutLoadingTheTarget() {
        Long customerId = db.orm().inTransaction(session -> {
            Customer customer = TestDb.customer("Ada", "ada@x.io");
            session.persist(customer);
            return customer;
        }).getId();
        db.counter().reset();
        db.orm().runInTransaction(session -> {
            Customer reference = session.getReference(Customer.class, customerId);
            session.persist(new PurchaseOrder(reference, BigDecimal.ONE));
        });
        assertThat(db.counter().statements()).as("the customer row is never read")
                .noneMatch(sql -> sql.contains("from \"customer\""));
        db.orm().runInSession(session ->
                assertThat(session.from(PurchaseOrder.class).first().orElseThrow().getCustomer().getId())
                        .isEqualTo(customerId));
    }

    // ------------------------------------------------------------------ collections

    @Test
    void aLazyCollectionLoadsOnFirstAccessAndNotBefore() {
        seed();
        db.orm().runInSession(session -> {
            Customer customer = session.from(Customer.class).first().orElseThrow();
            db.counter().reset();
            assertThat(MiniOrm.isInitialized(customer.getOrders())).isFalse();
            assertThat(customer.getOrders().toString()).contains("not loaded");
            assertThat(db.counter().count()).as("toString must not load it").isZero();

            assertThat(customer.getOrders()).hasSize(3);
            assertThat(db.counter().count(Kind.SELECT)).isEqualTo(1);
            assertThat(customer.getOrders()).hasSize(3);
            assertThat(db.counter().count(Kind.SELECT)).isEqualTo(1);
            assertThat(customer.getOrders()).allSatisfy(o -> assertThat(o.getCustomer()).isSameAs(customer));
        });
    }

    @Test
    void aCollectionUsedAfterTheSessionClosedFailsWithAClearException() {
        seed();
        Customer customer = db.orm().inSession(session -> session.from(Customer.class).first().orElseThrow());
        assertThatThrownBy(() -> customer.getOrders().size()).isInstanceOf(LazyInitializationException.class)
                .hasMessageContaining("Customer.orders").hasMessageContaining("closed");
    }

    @Test
    void aCollectionOfADetachedOwnerCannotBeLoaded() {
        seed();
        db.orm().runInSession(session -> {
            Customer customer = session.from(Customer.class).first().orElseThrow();
            session.detach(customer);
            assertThatThrownBy(() -> customer.getOrders().size()).isInstanceOf(LazyInitializationException.class)
                    .hasMessageContaining("detached");
        });
    }

    // ------------------------------------------------------------------ eager references

    @Test
    void aSetOfEagerReferencesIsLoadedWithOneInQueryNotOnePerRow() {
        seed();
        db.orm().runInSession(session -> {
            List<OrderLine> lines = session.from(OrderLine.class).list();
            assertThat(lines).hasSize(15);
            assertThat(db.counter().count(Kind.SELECT)).as("lines, then one IN query for their orders").isEqualTo(2);
            assertThat(lines).allSatisfy(line -> {
                assertThat(line.getOrder()).isNotInstanceOf(EntityProxy.class);
                assertThat(line.getOrder().getTotal()).isNotNull();
            });
            assertThat(db.counter().count(Kind.SELECT)).as("eager values are really loaded").isEqualTo(2);
        });
    }

    @Test
    void eagerLoadingDoesNotMakeEntitiesLookDirty() {
        seed();
        db.orm().runInTransaction(session -> {
            session.from(OrderLine.class).list();
            assertThat(session.isDirty()).isFalse();
        });
        assertThat(db.counter().count(Kind.UPDATE)).isZero();
    }

    // ------------------------------------------------------------------ fetch plans and N+1

    @Test
    void walkingLazyReferencesInALoopIsAnNPlusOneAndTheDetectorSaysSo() {
        seed();
        NPlusOneDetector detector = new NPlusOneDetector(3);
        db.orm().addListener(detector);
        try {
            db.orm().runInSession(session -> {
                for (PurchaseOrder order : session.from(PurchaseOrder.class).list()) {
                    order.getCustomer().getName();
                }
            });
            assertThatThrownBy(detector::assertNone).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("N+1").hasMessageContaining("from \"customer\"");
            assertThat(detector.repeated().values()).containsExactly(5);
        } finally {
            db.orm().removeListener(detector);
        }
    }

    @Test
    void fetchJoinLoadsTheReferenceInTheSameStatement() {
        seed();
        NPlusOneDetector detector = new NPlusOneDetector(2);
        db.orm().addListener(detector);
        try {
            db.orm().runInSession(session -> {
                List<PurchaseOrder> orders = session.from(PurchaseOrder.class).fetch(PurchaseOrder::getCustomer).list();
                for (PurchaseOrder order : orders) {
                    assertThat(order.getCustomer()).isNotInstanceOf(EntityProxy.class);
                    assertThat(order.getCustomer().getName()).startsWith("Customer");
                }
                assertThat(db.counter().count(Kind.SELECT)).isEqualTo(1);
                assertThat(db.counter().statements().getFirst()).contains("left join");
            });
            detector.assertNone();
        } finally {
            db.orm().removeListener(detector);
        }
    }

    @Test
    void fetchBatchLoadsAllReferencesWithOneInQuery() {
        seed();
        db.orm().runInSession(session -> {
            List<PurchaseOrder> orders = session.from(PurchaseOrder.class)
                    .fetch(PurchaseOrder::getCustomer, FetchMode.BATCH).list();
            assertThat(orders).hasSize(15);
            orders.forEach(o -> o.getCustomer().getName());
            assertThat(db.counter().count(Kind.SELECT)).as("orders + one IN query").isEqualTo(2);
            assertThat(db.counter().statements().getLast()).contains(" in (");
        });
    }

    @Test
    void fetchBatchOfACollectionLoadsEveryOwnersChildrenWithOneQuery() {
        seed();
        db.orm().runInSession(session -> {
            List<Customer> customers = session.from(Customer.class).fetch(Customer::getOrders).list();
            assertThat(customers).hasSize(5);
            int total = 0;
            for (Customer customer : customers) {
                total += customer.getOrders().size();
            }
            assertThat(total).isEqualTo(15);
            assertThat(db.counter().count(Kind.SELECT)).as("customers + one IN query for all their orders")
                    .isEqualTo(2);
        });
    }

    @Test
    void fetchJoinOfACollectionIsDeduplicatedAndIncludesOwnersWithoutChildren() {
        seed();
        db.orm().runInTransaction(session -> session.persist(TestDb.customer("No orders", "none@x.io")));
        db.counter().reset();
        db.orm().runInSession(session -> {
            List<Customer> customers = session.from(Customer.class)
                    .fetch(Customer::getOrders, FetchMode.JOIN).list();
            assertThat(customers).hasSize(6);
            assertThat(customers).extracting(c -> c.getOrders().size()).containsExactlyInAnyOrder(3, 3, 3, 3, 3, 0);
            assertThat(db.counter().count(Kind.SELECT)).isEqualTo(1);
        });
    }

    @Test
    void aJoinFetchOfACollectionCannotBePaginated() {
        seed();
        db.orm().runInSession(session -> assertThatThrownBy(() -> session.from(Customer.class)
                .fetch(Customer::getOrders, FetchMode.JOIN).limit(2).list())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BATCH"));
    }

    @Test
    void aPaginatedBatchFetchStillLoadsEachPagesChildren() {
        seed();
        db.orm().runInSession(session -> {
            List<Customer> page = session.from(Customer.class).fetch(Customer::getOrders).limit(2).list();
            List<Integer> sizes = new ArrayList<>();
            page.forEach(c -> sizes.add(c.getOrders().size()));
            assertThat(sizes).containsExactly(3, 3);
            assertThat(db.counter().count(Kind.SELECT)).isEqualTo(2);
        });
    }
}
