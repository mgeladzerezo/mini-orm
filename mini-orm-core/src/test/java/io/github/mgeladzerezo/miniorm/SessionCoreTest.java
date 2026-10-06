package io.github.mgeladzerezo.miniorm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.miniorm.error.ConstraintViolationException;
import io.github.mgeladzerezo.miniorm.error.EntityNotFoundException;
import io.github.mgeladzerezo.miniorm.error.OrmException;
import io.github.mgeladzerezo.miniorm.error.TransactionException;
import io.github.mgeladzerezo.miniorm.error.TransientReferenceException;
import io.github.mgeladzerezo.miniorm.model.Address;
import io.github.mgeladzerezo.miniorm.model.Category;
import io.github.mgeladzerezo.miniorm.model.Customer;
import io.github.mgeladzerezo.miniorm.model.OrderLine;
import io.github.mgeladzerezo.miniorm.model.Product;
import io.github.mgeladzerezo.miniorm.model.PurchaseOrder;
import io.github.mgeladzerezo.miniorm.model.Tier;
import io.github.mgeladzerezo.miniorm.query.Criteria;
import io.github.mgeladzerezo.miniorm.sql.SqlEvent;
import io.github.mgeladzerezo.miniorm.sql.SqlEvent.Kind;
import io.github.mgeladzerezo.miniorm.support.OrmTest;
import io.github.mgeladzerezo.miniorm.support.TestDb;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The unit of work: identity map, persist and find, dirty checking by snapshot comparison,
 * flush ordering and batching, lifecycle callbacks, transactions.
 */
class SessionCoreTest extends OrmTest {

    private long countRows(String table) throws Exception {
        try (Connection c = db.connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from \"" + table + "\"")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Long persistCustomer(String name, String email) {
        return db.orm().inTransaction(session -> {
            Customer customer = TestDb.customer(name, email);
            session.persist(customer);
            return customer;
        }).getId();
    }

    // ------------------------------------------------------------------ persist and find

    @Test
    void roundTripsEveryMappedFieldOfACustomer() {
        Instant before = Instant.now().minusSeconds(1);
        Customer saved = db.orm().inTransaction(session -> {
            Customer customer = TestDb.customer("Ada", "ADA@Example.COM");
            customer.setTier(Tier.GOLD);
            customer.setNickname(Optional.of("countess"));
            customer.setPoints(42);
            session.persist(customer);
            assertThat(customer.getId()).as("identity ids are assigned when the INSERT runs").isNull();
            return customer;
        });

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPrePersistCalls()).isEqualTo(1);
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isAfter(before);

        db.orm().runInTransaction(session -> {
            Customer loaded = session.find(Customer.class, saved.getId());
            assertThat(loaded).isNotSameAs(saved);
            assertThat(loaded.getName()).isEqualTo("Ada");
            assertThat(loaded.getEmail()).as("@PrePersist lower-cased it").isEqualTo("ada@example.com");
            assertThat(loaded.getAddress()).isEqualTo(new Address("1 Main St", "Tbilisi"));
            assertThat(loaded.getTier()).isEqualTo(Tier.GOLD);
            assertThat(loaded.getNickname()).contains("countess");
            assertThat(loaded.getPoints()).isEqualTo(42);
            assertThat(loaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
            assertThat(loaded.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
        });
    }

    @Test
    void roundTripsBasicTypesBinaryDecimalAndJson() {
        db.orm().runInTransaction(session -> {
            Product product = new Product("SKU-1", "Widget", new BigDecimal("19.90"));
            product.setWeightKg(1.25);
            product.setActive(false);
            product.setReleasedOn(LocalDate.of(2025, 12, 31));
            product.setImage(new byte[] {1, 2, 3, 127, -128});
            product.setTags(List.of("a", "b"));
            session.persist(product);
        });
        db.orm().runInTransaction(session -> {
            Product loaded = session.find(Product.class, "SKU-1");
            assertThat(loaded.getPrice()).isEqualByComparingTo("19.90");
            assertThat(loaded.getWeightKg()).isEqualTo(1.25);
            assertThat(loaded.isActive()).isFalse();
            assertThat(loaded.getReleasedOn()).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(loaded.getImage()).containsExactly(1, 2, 3, 127, -128);
            assertThat(loaded.getTags()).containsExactly("a", "b");
        });
    }

    @Test
    void findOfAMissingRowReturnsNull() {
        db.orm().runInSession(session -> assertThat(session.find(Customer.class, 12345L)).isNull());
    }

    @Test
    void aRowIsOneInstanceWithinASessionAndTheSecondFindCostsNoQuery() {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInSession(session -> {
            db.counter().reset();
            Customer first = session.find(Customer.class, id);
            Customer second = session.find(Customer.class, id);
            Customer viaQuery = session.from(Customer.class).where(Customer::getId, Criteria.eq(id)).one().orElseThrow();
            assertThat(second).isSameAs(first);
            assertThat(viaQuery).as("a query returns the managed instance, not a copy").isSameAs(first);
            assertThat(db.counter().count(Kind.SELECT)).isEqualTo(2);
        });
    }

    @Test
    void anIdOfAnotherNumericTypeIsConverted() {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInSession(session -> assertThat(session.find(Customer.class, id.intValue())).isNotNull());
    }

    // ------------------------------------------------------------------ dirty checking

    @Test
    void anUnchangedEntityProducesNoUpdate() {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            customer.getName(); // reading is not modifying
            db.counter().reset();
        });
        assertThat(db.counter().count(Kind.UPDATE)).isZero();
        assertThat(db.counter().count(Kind.INSERT)).isZero();
    }

    @Test
    void aChangedEntityUpdatesOnlyTheChangedColumnsPlusVersionAndTimestamp() {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            db.counter().reset();
            customer.setName("Ada Lovelace");
        });
        List<SqlEvent> updates = db.counter().events().stream().filter(e -> e.kind() == Kind.UPDATE).toList();
        assertThat(updates).hasSize(1);
        String sql = updates.getFirst().sql();
        assertThat(sql).contains("\"name\" = ?", "\"version\" = ?", "\"updated_at\" = ?")
                .doesNotContain("\"email\"", "\"tier\"", "\"points\"");
        assertThat(sql).endsWith("where \"id\" = ? and \"version\" = ?");
    }

    @Test
    void mutatingAnEmbeddedRecordAnOptionalAndAJsonListInPlaceIsDetected() {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            customer.setAddress(new Address("2 Side St", "Batumi"));
            customer.setNickname(Optional.of("al"));
        });
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            assertThat(customer.getAddress().city()).isEqualTo("Batumi");
            assertThat(customer.getNickname()).contains("al");
            customer.setAddress(null);
            customer.setNickname(Optional.empty());
        });
        db.orm().runInSession(session -> {
            Customer customer = session.find(Customer.class, id);
            assertThat(customer.getAddress()).isNull();
            assertThat(customer.getNickname()).isEmpty();
        });
    }

    @Test
    void mutatingAByteArrayOrAMutableJsonValueInPlaceIsDetected() {
        db.orm().runInTransaction(session -> {
            Product product = new Product("P", "Thing", new BigDecimal("1.00"));
            product.setImage(new byte[] {1, 2, 3});
            product.setTags(new ArrayList<>(List.of("x")));
            session.persist(product);
        });
        db.orm().runInTransaction(session -> {
            Product product = session.find(Product.class, "P");
            product.getImage()[0] = 99;
            product.getTags().add("y");
        });
        db.orm().runInSession(session -> {
            Product product = session.find(Product.class, "P");
            assertThat(product.getImage()).containsExactly(99, 2, 3);
            assertThat(product.getTags()).containsExactly("x", "y");
        });
    }

    @Test
    void settingAValueBackToItsOriginalLeavesNothingToUpdate() {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            db.counter().reset();
            customer.setName("Changed");
            customer.setName("Ada");
            assertThat(session.isDirty()).isFalse();
        });
        assertThat(db.counter().count(Kind.UPDATE)).isZero();
    }

    @Test
    void aDecimalWithADifferentScaleIsNotDirty() {
        db.orm().runInTransaction(session -> session.persist(new Product("P", "Thing", new BigDecimal("5.5"))));
        db.orm().runInTransaction(session -> {
            Product product = session.find(Product.class, "P");
            product.setPrice(new BigDecimal("5.50"));
            assertThat(session.isDirty()).isFalse();
        });
    }

    @Test
    void anEntityPersistedAndFlushedInTheSameSessionIsCleanAfterwards() {
        db.orm().runInTransaction(session -> {
            Customer customer = TestDb.customer("Ada", "ada@x.io");
            session.persist(customer);
            session.flush();
            assertThat(session.isDirty()).as("the clock's nanoseconds must not make it look modified").isFalse();
        });
        assertThat(db.counter().count(Kind.UPDATE)).isZero();
    }

    @Test
    void anUpdateBumpsTheVersionAndRunsPreUpdateOnlyForDirtyEntities() {
        Long id = persistCustomer("Ada", "ada@x.io");
        Instant[] firstUpdate = new Instant[1];
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            customer.setName("Ada L.");
            session.flush();
            assertThat(customer.getVersion()).isEqualTo(1);
            assertThat(customer.getPreUpdateCalls()).isEqualTo(1);
            firstUpdate[0] = customer.getUpdatedAt();
            session.flush();
            assertThat(customer.getPreUpdateCalls()).as("a clean flush must not call @PreUpdate").isEqualTo(1);
            customer.setName("Ada L2");
        });
        db.orm().runInSession(session -> {
            Customer customer = session.find(Customer.class, id);
            assertThat(customer.getVersion()).isEqualTo(2);
            assertThat(customer.getUpdatedAt()).isAfterOrEqualTo(firstUpdate[0]);
        });
    }

    // ------------------------------------------------------------------ remove, detach, refresh, merge

    @Test
    void removeDeletesTheRowAtFlush() throws Exception {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            session.remove(customer);
            assertThat(session.find(Customer.class, id)).as("scheduled for removal").isNull();
        });
        assertThat(countRows("customer")).isZero();
    }

    @Test
    void removingANewEntityThatWasNeverFlushedInsertsNothing() throws Exception {
        db.orm().runInTransaction(session -> {
            Customer customer = TestDb.customer("Ada", "ada@x.io");
            session.persist(customer);
            session.remove(customer);
        });
        assertThat(countRows("customer")).isZero();
        assertThat(db.counter().count(Kind.INSERT)).isZero();
    }

    @Test
    void removingAnEntityTheSessionDoesNotManageIsRejected() {
        db.orm().runInTransaction(session -> assertThatThrownBy(() -> session.remove(TestDb.customer("A", "a@x.io")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not managed"));
    }

    @Test
    void persistingTwiceOrAfterRemoveIsHarmlessAndCancelsTheRemoval() throws Exception {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            session.remove(customer);
            session.persist(customer);
            session.persist(customer);
        });
        assertThat(countRows("customer")).isEqualTo(1);
    }

    @Test
    void aDetachedEntityIsNotFlushed() {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            session.detach(customer);
            customer.setName("ignored");
            assertThat(session.contains(customer)).isFalse();
        });
        db.orm().runInSession(session -> assertThat(session.find(Customer.class, id).getName()).isEqualTo("Ada"));
    }

    @Test
    void refreshDiscardsUnflushedChangesAndReloadsTheRow() {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            session.nativeUpdate("update \"customer\" set \"name\" = ? where \"id\" = ?", "Changed elsewhere", id);
            customer.setName("local edit");
            session.refresh(customer);
            assertThat(customer.getName()).isEqualTo("Changed elsewhere");
            assertThat(session.isDirty()).isFalse();
        });
    }

    @Test
    void refreshOfADeletedRowFailsClearly() {
        Long id = persistCustomer("Ada", "ada@x.io");
        db.orm().runInTransaction(session -> {
            Customer customer = session.find(Customer.class, id);
            session.nativeUpdate("delete from \"customer\" where \"id\" = ?", id);
            assertThatThrownBy(() -> session.refresh(customer)).isInstanceOf(EntityNotFoundException.class);
        });
    }

    @Test
    void mergeCopiesDetachedStateOntoTheManagedInstance() {
        Customer detached = db.orm().inTransaction(session -> {
            Customer customer = TestDb.customer("Ada", "ada@x.io");
            session.persist(customer);
            return customer;
        });
        detached.setName("Edited offline");
        db.orm().runInTransaction(session -> {
            Customer managed = session.merge(detached);
            assertThat(managed).isNotSameAs(detached);
            assertThat(session.contains(managed)).isTrue();
            assertThat(managed.getName()).isEqualTo("Edited offline");
        });
        db.orm().runInSession(session ->
                assertThat(session.find(Customer.class, detached.getId()).getName()).isEqualTo("Edited offline"));
    }

    @Test
    void mergeOfANewInstanceInsertsACopy() {
        Customer fresh = TestDb.customer("Grace", "grace@x.io");
        Customer managed = db.orm().inTransaction(session -> session.merge(fresh));
        assertThat(managed.getId()).isNotNull();
        assertThat(fresh.getId()).as("the argument itself is left alone").isNull();
    }

    // ------------------------------------------------------------------ flush ordering and batching

    @Test
    void rowsAreInsertedInDependencyOrderWhateverTheOrderOfPersist() throws Exception {
        db.orm().runInTransaction(session -> {
            Product product = new Product("P1", "Thing", new BigDecimal("2.00"));
            Customer customer = TestDb.customer("Ada", "ada@x.io");
            PurchaseOrder order = new PurchaseOrder(customer, new BigDecimal("4.00"));
            OrderLine line = new OrderLine(order, product, 2);
            // Deliberately the reverse of the order the foreign keys need.
            session.persist(line);
            session.persist(order);
            session.persist(customer);
            session.persist(product);
        });
        assertThat(countRows("order_line")).isEqualTo(1);
        List<String> inserts = db.counter().events().stream().filter(e -> e.kind() == Kind.INSERT)
                .map(e -> e.sql().split("\"")[1]).toList();
        assertThat(inserts.indexOf("customer")).isLessThan(inserts.indexOf("purchase_orders"));
        assertThat(inserts.indexOf("purchase_orders")).isLessThan(inserts.indexOf("order_line"));
        assertThat(inserts.indexOf("product")).isLessThan(inserts.indexOf("order_line"));
    }

    @Test
    void aChainOfSelfReferencingRowsWithIdentityKeysInsertsParentsFirst() throws Exception {
        db.orm().runInTransaction(session -> {
            Category root = new Category("root", null);
            Category middle = new Category("middle", root);
            Category leaf = new Category("leaf", middle);
            session.persist(leaf);
            session.persist(middle);
            session.persist(root);
        });
        db.orm().runInSession(session -> {
            Category leaf = session.from(Category.class).where(Category::getName, Criteria.eq("leaf")).one().orElseThrow();
            assertThat(leaf.getParent().getName()).isEqualTo("middle");
            assertThat(leaf.getParent().getParent().getName()).isEqualTo("root");
            assertThat(leaf.getParent().getParent().getParent()).isNull();
        });
    }

    @Test
    void manyRowsOfTheSameTypeGoOutAsOneJdbcBatch() throws Exception {
        db.orm().runInTransaction(session -> {
            for (int i = 0; i < 50; i++) {
                session.persist(new Product("S" + i, "Item " + i, BigDecimal.TEN));
            }
        });
        List<SqlEvent> inserts = db.counter().events().stream().filter(e -> e.kind() == Kind.INSERT).toList();
        assertThat(inserts).hasSize(1);
        assertThat(inserts.getFirst().batchSize()).isEqualTo(50);
        assertThat(countRows("product")).isEqualTo(50);
    }

    @Test
    void identityKeysAreRetrievedForAWholeBatchOfInserts() throws Exception {
        List<Customer> customers = new ArrayList<>();
        db.orm().runInTransaction(session -> {
            for (int i = 0; i < 20; i++) {
                Customer customer = TestDb.customer("C" + i, "c" + i + "@x.io");
                customers.add(customer);
                session.persist(customer);
            }
        });
        assertThat(db.counter().events().stream().filter(e -> e.kind() == Kind.INSERT)).hasSize(1);
        Set<Long> ids = new HashSet<>();
        customers.forEach(c -> ids.add(c.getId()));
        assertThat(ids).hasSize(20).doesNotContainNull();
        // The generated key must belong to the right instance: read each row back by id.
        db.orm().runInSession(session -> customers.forEach(c ->
                assertThat(session.find(Customer.class, c.getId()).getName()).isEqualTo(c.getName())));
    }

    @Test
    void manyUpdatesOfTheSameShapeGoOutAsOneBatch() {
        db.orm().runInTransaction(session -> {
            for (int i = 0; i < 10; i++) {
                session.persist(new Product("U" + i, "Item", BigDecimal.ONE));
            }
        });
        db.orm().runInTransaction(session -> {
            List<Product> products = session.from(Product.class).list();
            db.counter().reset();
            products.forEach(p -> p.setName("Renamed"));
        });
        List<SqlEvent> updates = db.counter().events().stream().filter(e -> e.kind() == Kind.UPDATE).toList();
        assertThat(updates).hasSize(1);
        assertThat(updates.getFirst().batchSize()).isEqualTo(10);
    }

    @Test
    void sequenceIdsAreAllocatedInBlocksAndAreUnique() {
        Customer customer = db.orm().inTransaction(session -> {
            Customer c = TestDb.customer("Ada", "ada@x.io");
            session.persist(c);
            return c;
        });
        List<PurchaseOrder> orders = new ArrayList<>();
        db.counter().reset();
        db.orm().runInTransaction(session -> {
            Customer managed = session.find(Customer.class, customer.getId());
            for (int i = 0; i < 25; i++) {
                PurchaseOrder order = new PurchaseOrder(managed, BigDecimal.valueOf(i));
                orders.add(order);
                session.persist(order);
                assertThat(order.getId()).as("sequence ids exist before the flush").isNotNull();
            }
        });
        assertThat(orders.stream().map(PurchaseOrder::getId).distinct()).hasSize(25);
        long sequenceCalls = db.counter().statements().stream().filter(s -> s.contains("purchase_order_seq")).count();
        assertThat(sequenceCalls).as("allocation size 10 for 25 ids, minus ids left over from earlier tests")
                .isBetween(2L, 3L);
    }

    @Test
    void anAssignedIdIsRequiredAndAGeneratedOneRejected() {
        db.orm().runInTransaction(session -> {
            Product noId = new Product(null, "x", BigDecimal.ONE);
            assertThatThrownBy(() -> session.persist(noId)).isInstanceOf(OrmException.class)
                    .hasMessageContaining("must be assigned");
        });
        Customer detached = db.orm().inTransaction(session -> {
            Customer c = TestDb.customer("Ada", "ada@x.io");
            session.persist(c);
            return c;
        });
        db.orm().runInTransaction(session -> assertThatThrownBy(() -> session.persist(detached))
                .isInstanceOf(OrmException.class).hasMessageContaining("detached"));
    }

    @Test
    void referencingAnEntityThatWasNeverPersistedIsReportedAtFlush() {
        assertThatThrownBy(() -> db.orm().runInTransaction(session -> {
            Customer ghost = TestDb.customer("Ghost", "g@x.io");
            session.persist(new PurchaseOrder(ghost, BigDecimal.ONE));
        })).isInstanceOf(TransientReferenceException.class).hasMessageContaining("PurchaseOrder.customer");
    }

    @Test
    void deletingParentAndChildInOneFlushDeletesTheChildFirst() throws Exception {
        db.orm().runInTransaction(session -> {
            Category root = new Category("root", null);
            Category leaf = new Category("leaf", root);
            session.persist(root);
            session.persist(leaf);
        });
        db.orm().runInTransaction(session -> {
            List<Category> all = session.from(Category.class).list();
            // Remove the parent first: the flush must still delete the referencing row before it.
            all.stream().filter(c -> c.getName().equals("root")).forEach(session::remove);
            all.stream().filter(c -> c.getName().equals("leaf")).forEach(session::remove);
        });
        assertThat(countRows("category")).isZero();
    }

    @Test
    void aUniqueConstraintViolationSurfacesAsAConstraintViolationException() {
        persistCustomer("Ada", "dup@x.io");
        assertThatThrownBy(() -> persistCustomer("Other", "dup@x.io"))
                .isInstanceOf(ConstraintViolationException.class);
    }

    // ------------------------------------------------------------------ transactions

    @Test
    void anExceptionRollsBackEverythingAndLeavesTheSessionUsable() throws Exception {
        try (Session session = db.orm().openSession()) {
            assertThatThrownBy(() -> session.runInTransaction(tx -> {
                session.persist(TestDb.customer("Ada", "ada@x.io"));
                session.flush();
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
            assertThat(countRows("customer")).isZero();
            session.runInTransaction(tx -> session.persist(TestDb.customer("Grace", "grace@x.io")));
        }
        assertThat(countRows("customer")).isEqualTo(1);
    }

    @Test
    void aFailureInsideANestedCallPoisonsTheOuterTransaction() throws Exception {
        try (Session session = db.orm().openSession()) {
            assertThatThrownBy(() -> session.runInTransaction(outer -> {
                session.persist(TestDb.customer("Ada", "ada@x.io"));
                try {
                    session.runInTransaction(inner -> {
                        throw new IllegalStateException("inner failed");
                    });
                } catch (IllegalStateException swallowed) {
                    // the outer work carries on, but the transaction can no longer commit
                }
            })).isInstanceOf(TransactionException.class).hasMessageContaining("rollback-only");
        }
        assertThat(countRows("customer")).isZero();
    }

    @Test
    void aNestedCallThatSucceedsSharesTheOuterTransaction() throws Exception {
        db.orm().runInTransaction(session -> {
            session.persist(TestDb.customer("Ada", "ada@x.io"));
            session.runInTransaction(inner -> session.persist(TestDb.customer("Grace", "grace@x.io")));
            assertThat(session.isInTransaction()).isTrue();
        });
        assertThat(countRows("customer")).isEqualTo(2);
    }

    @Test
    void aSavepointRollsBackOnePartWhileTheRestCommits() throws Exception {
        db.orm().runInTransaction(session -> session.runInTransaction(tx -> {
            session.persist(TestDb.customer("Kept", "kept@x.io"));
            assertThatThrownBy(() -> tx.inSavepoint(() -> {
                session.persist(TestDb.customer("Lost", "lost@x.io"));
                session.flush();
                throw new IllegalStateException("undo this part");
            })).isInstanceOf(IllegalStateException.class);
            session.persist(TestDb.customer("Also kept", "also@x.io"));
        }));
        db.orm().runInSession(session -> assertThat(session.from(Customer.class).list())
                .extracting(Customer::getName).containsExactlyInAnyOrder("Kept", "Also kept"));
    }

    @Test
    void aSavepointRecoversFromADatabaseErrorThatWouldAbortAPostgresTransaction() {
        persistCustomer("Existing", "taken@x.io");
        db.orm().runInTransaction(session -> session.runInTransaction(tx -> {
            session.persist(TestDb.customer("Fine", "fine@x.io"));
            assertThatThrownBy(() -> tx.inSavepoint(() -> {
                session.persist(TestDb.customer("Duplicate", "taken@x.io"));
                session.flush();
                return null;
            })).isInstanceOf(ConstraintViolationException.class);
            // Without the savepoint PostgreSQL would now refuse every statement.
            assertThat(session.from(Customer.class).count()).isGreaterThanOrEqualTo(1);
        }));
    }

    @Test
    void flushAndLocksNeedATransactionButReadsDoNot() {
        Long id = persistCustomer("Ada", "ada@x.io");
        try (Session session = db.orm().openSession()) {
            assertThat(session.from(Customer.class).count()).isEqualTo(1);
            assertThatThrownBy(session::flush).isInstanceOf(TransactionException.class);
            assertThatThrownBy(() -> session.find(Customer.class, id, LockMode.FOR_UPDATE))
                    .isInstanceOf(TransactionException.class);
        }
    }

    @Test
    void aClosedSessionRejectsWork() {
        Session session = db.orm().openSession();
        session.close();
        assertThat(session.isClosed()).isTrue();
        assertThatThrownBy(() -> session.find(Customer.class, 1L)).isInstanceOf(IllegalStateException.class);
        session.close();
    }

    @Test
    void closingTheSessionReturnsTheConnectionToThePool() {
        Long id = persistCustomer("Ada", "ada@x.io");
        try (Session session = db.orm().openSession()) {
            session.find(Customer.class, id);
            assertThat(db.pool().metrics().active()).isEqualTo(1);
        }
        assertThat(db.pool().metrics().active()).isZero();
    }

    @Test
    void autoFlushLetsAQuerySeeTheSessionsOwnPendingInsert() {
        db.orm().runInTransaction(session -> {
            session.persist(TestDb.customer("Ada", "ada@x.io"));
            assertThat(session.from(Customer.class).count()).isEqualTo(1);
        });
    }
}
