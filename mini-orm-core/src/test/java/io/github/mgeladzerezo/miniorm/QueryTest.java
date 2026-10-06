package io.github.mgeladzerezo.miniorm;

import static io.github.mgeladzerezo.miniorm.query.Criteria.between;
import static io.github.mgeladzerezo.miniorm.query.Criteria.contains;
import static io.github.mgeladzerezo.miniorm.query.Criteria.endsWith;
import static io.github.mgeladzerezo.miniorm.query.Criteria.eq;
import static io.github.mgeladzerezo.miniorm.query.Criteria.ge;
import static io.github.mgeladzerezo.miniorm.query.Criteria.gt;
import static io.github.mgeladzerezo.miniorm.query.Criteria.ilike;
import static io.github.mgeladzerezo.miniorm.query.Criteria.in;
import static io.github.mgeladzerezo.miniorm.query.Criteria.isNotNull;
import static io.github.mgeladzerezo.miniorm.query.Criteria.isNull;
import static io.github.mgeladzerezo.miniorm.query.Criteria.le;
import static io.github.mgeladzerezo.miniorm.query.Criteria.like;
import static io.github.mgeladzerezo.miniorm.query.Criteria.lt;
import static io.github.mgeladzerezo.miniorm.query.Criteria.ne;
import static io.github.mgeladzerezo.miniorm.query.Criteria.notIn;
import static io.github.mgeladzerezo.miniorm.query.Criteria.startsWith;
import static io.github.mgeladzerezo.miniorm.query.Selections.avg;
import static io.github.mgeladzerezo.miniorm.query.Selections.col;
import static io.github.mgeladzerezo.miniorm.query.Selections.count;
import static io.github.mgeladzerezo.miniorm.query.Selections.max;
import static io.github.mgeladzerezo.miniorm.query.Selections.min;
import static io.github.mgeladzerezo.miniorm.query.Selections.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.error.NonUniqueResultException;
import io.github.mgeladzerezo.miniorm.model.Address;
import io.github.mgeladzerezo.miniorm.model.Customer;
import io.github.mgeladzerezo.miniorm.model.Product;
import io.github.mgeladzerezo.miniorm.model.PurchaseOrder;
import io.github.mgeladzerezo.miniorm.model.Tier;
import io.github.mgeladzerezo.miniorm.query.Attribute;
import io.github.mgeladzerezo.miniorm.query.Condition;
import io.github.mgeladzerezo.miniorm.query.Page;
import io.github.mgeladzerezo.miniorm.query.Pageable;
import io.github.mgeladzerezo.miniorm.query.Sort;
import io.github.mgeladzerezo.miniorm.sql.SqlEvent.Kind;
import io.github.mgeladzerezo.miniorm.support.OrmTest;
import io.github.mgeladzerezo.miniorm.support.TestDb;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The type-safe query API rendered for both dialects: operators, composition, paging, projections, injection. */
class QueryTest extends OrmTest {

    private static final Attribute<Customer, String> CITY = Attribute.of(Customer::getAddress).then(Address::city);

    @BeforeEach
    void seed() {
        db.orm().runInTransaction(session -> {
            customer(session, "Ada", "ada@x.io", Tier.GOLD, 30, "Tbilisi", "countess");
            customer(session, "Alan", "alan@x.io", Tier.BASIC, 20, "Batumi", null);
            customer(session, "Grace", "grace@x.io", Tier.GOLD, 50, "Tbilisi", null);
            customer(session, "Linus", "linus@x.io", Tier.BASIC, 10, "Kutaisi", "tux");
            customer(session, "100%_real", "pct@x.io", Tier.BASIC, 0, "Poti", null);
        });
        db.counter().reset();
    }

    private static void customer(Session session, String name, String email, Tier tier, int points, String city,
                                 String nickname) {
        Customer customer = new Customer(name, email);
        customer.setTier(tier);
        customer.setPoints(points);
        customer.setAddress(new Address("1 Main St", city));
        customer.setNickname(Optional.ofNullable(nickname));
        session.persist(customer);
    }

    private List<String> names(List<Customer> customers) {
        return customers.stream().map(Customer::getName).toList();
    }

    // ------------------------------------------------------------------ operators

    @Test
    void comparisonOperatorsFilterAsExpected() {
        db.orm().runInSession(session -> {
            assertThat(names(session.from(Customer.class).where(Customer::getPoints, eq(30)).list())).containsExactly("Ada");
            assertThat(session.from(Customer.class).where(Customer::getPoints, ne(30)).count()).isEqualTo(4);
            assertThat(session.from(Customer.class).where(Customer::getPoints, gt(30)).count()).isEqualTo(1);
            assertThat(session.from(Customer.class).where(Customer::getPoints, ge(30)).count()).isEqualTo(2);
            assertThat(session.from(Customer.class).where(Customer::getPoints, lt(10)).count()).isEqualTo(1);
            assertThat(session.from(Customer.class).where(Customer::getPoints, le(10)).count()).isEqualTo(2);
            assertThat(session.from(Customer.class).where(Customer::getPoints, between(10, 30)).count()).isEqualTo(3);
        });
    }

    @Test
    void membershipNullAndEnumPredicates() {
        db.orm().runInSession(session -> {
            assertThat(session.from(Customer.class).where(Customer::getName, in("Ada", "Grace", "Nobody")).count())
                    .isEqualTo(2);
            assertThat(session.from(Customer.class).where(Customer::getName, notIn(List.of("Ada", "Grace"))).count())
                    .isEqualTo(3);
            assertThat(session.from(Customer.class).where(Customer::getTier, eq(Tier.GOLD)).count()).isEqualTo(2);
            assertThat(session.from(Customer.class).where(Customer::getName, in(List.of())).count())
                    .as("an empty IN matches nothing").isZero();
            assertThat(session.from(Customer.class).where(Customer::getName, notIn(List.of())).count())
                    .as("an empty NOT IN matches everything").isEqualTo(5);
        });
    }

    @Test
    void optionalColumnsAreComparedThroughTheirContent() {
        db.orm().runInSession(session -> {
            assertThat(names(session.from(Customer.class).where(Customer::getNickname, eq(Optional.of("tux"))).list()))
                    .containsExactly("Linus");
            assertThat(session.from(Customer.class).where(Customer::getNickname, eq(Optional.empty())).count())
                    .as("an empty Optional means IS NULL").isEqualTo(3);
        });
    }

    @Test
    void likeFamilyBindsItsPatternAndEscapesWildcardsInUserText() {
        db.orm().runInSession(session -> {
            assertThat(names(session.from(Customer.class).where(Customer::getName, startsWith("Al")).list()))
                    .containsExactly("Alan");
            assertThat(names(session.from(Customer.class).where(Customer::getEmail, endsWith("@x.io"))
                    .orderBy(Sort.asc(Customer::getName)).list())).hasSize(5);
            assertThat(names(session.from(Customer.class).where(Customer::getName, like("G%e")).list()))
                    .containsExactly("Grace");
            assertThat(names(session.from(Customer.class).where(Customer::getName, ilike("ADA")).list()))
                    .containsExactly("Ada");
            assertThat(names(session.from(Customer.class).where(Customer::getName, contains("%")).list()))
                    .as("a percent sign typed by a user is a literal percent sign").containsExactly("100%_real");
            assertThat(names(session.from(Customer.class).where(Customer::getName, contains("_")).list()))
                    .containsExactly("100%_real");
            assertThat(session.from(Customer.class).where(Customer::getName, contains("a")).count())
                    .isEqualTo(4);
        });
    }

    @Test
    void embeddedPropertiesAreAddressedThroughAnAttributePath() {
        db.orm().runInSession(session ->
                assertThat(names(session.from(Customer.class).where(CITY, eq("Tbilisi"))
                        .orderBy(Sort.asc(Customer::getName)).list())).containsExactly("Ada", "Grace"));
    }

    @Test
    void anAssociationIsComparedByItsForeignKey() {
        Customer ada = db.orm().inTransaction(session -> {
            Customer found = session.from(Customer.class).where(Customer::getName, eq("Ada")).one().orElseThrow();
            session.persist(new PurchaseOrder(found, BigDecimal.TEN));
            session.persist(new PurchaseOrder(session.from(Customer.class).where(Customer::getName, eq("Alan"))
                    .one().orElseThrow(), BigDecimal.ONE));
            return found;
        });
        db.orm().runInSession(session -> {
            Customer reference = session.getReference(Customer.class, ada.getId());
            assertThat(session.from(PurchaseOrder.class).where(PurchaseOrder::getCustomer, eq(reference)).list())
                    .extracting(PurchaseOrder::getTotal).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .containsExactly(BigDecimal.TEN);
        });
    }

    // ------------------------------------------------------------------ composition

    @Test
    void andOrAndConditionsComposeWithTheExpectedGrouping() {
        db.orm().runInSession(session -> {
            // left to right: (tier = GOLD and points > 40) or name = Linus
            assertThat(names(session.from(Customer.class).where(Customer::getTier, eq(Tier.GOLD))
                    .and(Customer::getPoints, gt(40)).or(Customer::getName, eq("Linus"))
                    .orderBy(Sort.asc(Customer::getName)).list())).containsExactly("Grace", "Linus");

            Condition<Customer> goldOrYoung = Condition.where(Customer::getTier, eq(Tier.GOLD))
                    .or(Customer::getPoints, lt(15));
            // tier = BASIC and (tier = GOLD or points < 15) -> only the BASIC ones under 15
            assertThat(names(session.from(Customer.class).where(Customer::getTier, eq(Tier.BASIC))
                    .and(goldOrYoung).orderBy(Sort.asc(Customer::getName)).list()))
                    .containsExactly("100%_real", "Linus");
            assertThat(names(session.from(Customer.class).where(goldOrYoung.not())
                    .orderBy(Sort.asc(Customer::getName)).list())).containsExactly("Alan");
        });
    }

    @Test
    void sortingLimitAndOffsetApplyInThatOrder() {
        db.orm().runInSession(session -> {
            assertThat(names(session.from(Customer.class).orderBy(Sort.desc(Customer::getPoints)).limit(2).list()))
                    .containsExactly("Grace", "Ada");
            assertThat(names(session.from(Customer.class).orderBy(Sort.desc(Customer::getPoints)).offset(1).limit(2)
                    .list())).containsExactly("Ada", "Alan");
            assertThat(names(session.from(Customer.class).orderBy(Sort.asc(Customer::getTier)
                    .then(Sort.desc(Customer::getPoints))).list()))
                    .containsExactly("Alan", "Linus", "100%_real", "Grace", "Ada");
        });
    }

    // ------------------------------------------------------------------ terminal operations

    @Test
    void firstOneCountAndExists() {
        db.orm().runInSession(session -> {
            assertThat(session.from(Customer.class).orderBy(Sort.asc(Customer::getName)).first().orElseThrow()
                    .getName()).isEqualTo("100%_real");
            assertThat(session.from(Customer.class).where(Customer::getName, eq("none")).first()).isEmpty();
            assertThat(session.from(Customer.class).where(Customer::getName, eq("Ada")).one()).isPresent();
            assertThatThrownBy(() -> session.from(Customer.class).where(Customer::getTier, eq(Tier.GOLD)).one())
                    .isInstanceOf(NonUniqueResultException.class);
            assertThat(session.from(Customer.class).where(Customer::getTier, eq(Tier.GOLD)).limit(1).count())
                    .as("count ignores limit").isEqualTo(2);
            assertThat(session.from(Customer.class).where(Customer::getName, eq("Ada")).exists()).isTrue();
            assertThat(session.from(Customer.class).where(Customer::getName, eq("nobody")).exists()).isFalse();
        });
    }

    @Test
    void pageReturnsTheSliceAndTheTotal() {
        db.orm().runInSession(session -> {
            Page<Customer> second = session.from(Customer.class).page(Pageable.of(1, 2, Sort.asc(Customer::getName)));
            assertThat(names(second.content())).containsExactly("Alan", "Grace");
            assertThat(second.totalElements()).isEqualTo(5);
            assertThat(second.totalPages()).isEqualTo(3);
            assertThat(second.hasNext()).isTrue();
            assertThat(second.hasPrevious()).isTrue();

            Page<Customer> last = session.from(Customer.class).page(Pageable.of(2, 2, Sort.asc(Customer::getName)));
            assertThat(last.content()).hasSize(1);
            assertThat(last.hasNext()).isFalse();
        });
    }

    @Test
    void anUnsortedPageIsOrderedByIdSoPagesDoNotOverlap() {
        db.orm().runInSession(session -> {
            List<Long> ids = new java.util.ArrayList<>();
            for (int page = 0; page < 3; page++) {
                session.from(Customer.class).page(Pageable.of(page, 2)).content().forEach(c -> ids.add(c.getId()));
            }
            assertThat(ids).hasSize(5).doesNotHaveDuplicates().isSorted();
        });
    }

    // ------------------------------------------------------------------ projections

    record TierSummary(Tier tier, long customers, long totalPoints, Double averagePoints) {
    }

    @Test
    void aggregatesWithGroupByMapIntoRecords() {
        db.orm().runInSession(session -> {
            List<TierSummary> summary = session.from(Customer.class)
                    .select(col(Customer::getTier), count(), sum(Customer::getPoints), avg(Customer::getPoints))
                    .groupBy(Customer::getTier).into(TierSummary.class);
            assertThat(summary).hasSize(2);
            TierSummary gold = summary.stream().filter(s -> s.tier() == Tier.GOLD).findFirst().orElseThrow();
            assertThat(gold.customers()).isEqualTo(2);
            assertThat(gold.totalPoints()).isEqualTo(80);
            assertThat(gold.averagePoints()).isEqualTo(40.0);
        });
    }

    record NameAndCity(String name, String city) {
    }

    @Test
    void columnProjectionsDoNotMakeEntitiesManaged() {
        db.orm().runInTransaction(session -> {
            List<NameAndCity> rows = session.from(Customer.class).where(Customer::getTier, eq(Tier.GOLD))
                    .orderBy(Sort.asc(Customer::getName))
                    .select(col(Customer::getName), col(CITY)).into(NameAndCity.class);
            assertThat(rows).containsExactly(new NameAndCity("Ada", "Tbilisi"), new NameAndCity("Grace", "Tbilisi"));
            assertThat(session.isDirty()).isFalse();
        });
    }

    @Test
    void scalarAggregatesReturnTypedValues() {
        db.orm().runInSession(session -> {
            assertThat(session.from(Customer.class).select(max(Customer::getPoints)).scalar(Integer.class)).isEqualTo(50);
            assertThat(session.from(Customer.class).select(min(Customer::getName)).scalar(String.class))
                    .isEqualTo("100%_real");
            assertThat(session.from(Customer.class).select(count()).scalar(Long.class)).isEqualTo(5);
            assertThat(session.from(Customer.class).where(Customer::getName, eq("nobody"))
                    .select(max(Customer::getPoints)).scalar(Integer.class)).isNull();
        });
    }

    @Test
    void decimalAggregatesKeepTheirPrecision() {
        db.orm().runInTransaction(session -> {
            session.persist(new Product("A", "a", new BigDecimal("1.10")));
            session.persist(new Product("B", "b", new BigDecimal("2.25")));
        });
        db.orm().runInSession(session -> assertThat(
                session.from(Product.class).select(sum(Product::getPrice)).scalar(BigDecimal.class))
                .isEqualByComparingTo("3.35"));
    }

    // ------------------------------------------------------------------ safety

    @Test
    void valuesAreBoundNotConcatenatedSoInjectionAttemptsAreInert() throws Exception {
        String attack = "x'; drop table \"customer\"; --";
        db.orm().runInSession(session -> {
            assertThat(session.from(Customer.class).where(Customer::getName, eq(attack)).list()).isEmpty();
            assertThat(session.from(Customer.class).where(Customer::getName, in(attack, "' or '1'='1")).list()).isEmpty();
            assertThat(session.from(Customer.class).where(Customer::getName, contains("' or 1=1 --")).list()).isEmpty();
            assertThat(session.from(Customer.class).where(Customer::getEmail, like(attack)).count()).isZero();
        });
        db.counter().events().stream().filter(e -> e.kind() == Kind.SELECT).forEach(e ->
                assertThat(e.sql()).as("no value may appear in the SQL text").doesNotContain("drop table", "1=1"));
        try (Connection c = db.connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from \"customer\"")) {
            rs.next();
            assertThat(rs.getLong(1)).as("the table still exists with all rows").isEqualTo(5);
        }
    }

    @Test
    void anInjectionAttemptStoredAsDataRoundTripsUnchanged() {
        String attack = "Robert'); DROP TABLE \"customer\";--";
        db.orm().runInTransaction(session -> {
            Customer customer = new Customer(attack, "bobby@x.io");
            session.persist(customer);
        });
        db.orm().runInSession(session -> assertThat(
                session.from(Customer.class).where(Customer::getName, eq(attack)).one().orElseThrow().getName())
                .isEqualTo(attack));
    }

    @Test
    void anUnknownPropertyIsRejectedBeforeAnySqlIsSent() {
        db.counter().reset();
        db.orm().runInSession(session -> assertThatThrownBy(() -> session.from(Customer.class)
                .where(Attribute.<Customer, String>named("name; drop table customer"), eq("x")).list())
                .isInstanceOf(MappingException.class).hasMessageContaining("no persistent property"));
        assertThat(db.counter().count()).isZero();
    }

    @Test
    void aWronglyTypedOperandIsRejectedBeforeAnySqlIsSent() {
        db.counter().reset();
        db.orm().runInSession(session -> assertThatThrownBy(() -> session.from(Customer.class)
                .where(Attribute.<Customer, Object>named("points"), eq("not a number")).list())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("points")
                .hasMessageContaining("Integer"));
        assertThat(db.counter().count()).isZero();
    }

    @Test
    void aLambdaThatIsNotAGetterReferenceIsRejected() {
        db.orm().runInSession(session -> assertThatThrownBy(() -> session.from(Customer.class)
                .where(c -> c.getName().trim(), eq("x")).list())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("getter method reference"));
    }

    // ------------------------------------------------------------------ native

    @Test
    void nativeQueriesBindParametersAndMapRowsByHand() {
        db.orm().runInSession(session -> {
            List<String> names = session.nativeQuery(
                    "select \"name\" from \"customer\" where \"points\" >= ? order by \"name\"",
                    rs -> rs.getString(1), 30);
            assertThat(names).containsExactly("Ada", "Grace");
        });
    }

    @Test
    void aQuerySeesPendingChangesOfItsOwnSession() {
        db.orm().runInTransaction(session -> {
            Customer ada = session.from(Customer.class).where(Customer::getName, eq("Ada")).one().orElseThrow();
            ada.setName("Renamed");
            assertThat(session.from(Customer.class).where(Customer::getName, eq("Renamed")).count()).isEqualTo(1);
            assertThat(session.from(Customer.class).where(Customer::getName, isNotNull()).count()).isEqualTo(5);
            assertThat(session.from(Customer.class).where(Customer::getName, isNull()).count()).isZero();
        });
    }
}
