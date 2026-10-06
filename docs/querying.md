# Querying

```java
import static io.github.mgeladzerezo.miniorm.query.Criteria.*;

List<User> users = session.from(User.class)
        .where(User::getStatus, eq(Status.ACTIVE))
        .and(User::getAge, ge(18))
        .orderBy(Sort.desc(User::getCreatedAt))
        .limit(10)
        .list();
```

`User::getStatus` is a serialisable method reference. The query API reads the lambda's
`SerializedLambda` to learn the method name, maps `getX`/`isX`/`x` to the property `x`, and never
calls the getter. Because `Criterion<V>` ties the operand type to the property type,
`where(User::getAge, eq("x"))` is rejected. With an inline method reference the compiler may infer
a common supertype, so the check is repeated at run time, before any SQL is sent. An
`Attribute<User, Integer>` constant (`Attribute.of(User::getAge)`) gives strict compile-time typing
and names nested properties of embedded objects: `Attribute.of(User::getAddress).then(Address::city)`.

Operators: `eq ne gt ge lt le between in notIn isNull isNotNull like ilike contains
containsIgnoreCase startsWith endsWith`. `eq(null)` means `IS NULL`. `contains`, `startsWith` and
`endsWith` escape `%` and `_` in the text.

`and`/`or` combine left to right. For explicit grouping build a `Condition`:

```java
Condition<User> adultOrAdmin = Condition.where(User::getAge, ge(18)).or(User::getRole, eq(Role.ADMIN));
session.from(User.class).where(User::isActive, eq(true)).and(adultOrAdmin).list();
// where active = ? and (age >= ? or role = ?)
```

Terminal operations: `list()`, `first()`, `one()` (throws `NonUniqueResultException`), `count()`,
`exists()`, `page(Pageable)` returning `Page<T>` with the total.

## How SQL is produced

Predicates form an expression tree (`Expr`: predicate, and, or, not). A renderer walks it per
dialect. The only caller-supplied text that can reach SQL is a property name, which is looked up in
the entity metadata and replaced by the mapped column name; an unknown name is an error. Every
value, including LIKE patterns and pagination limits, is a bound `?`. `QueryTest` runs injection
strings through every operator and asserts the SQL text contains none of them.

## Projections and aggregates

```java
record TierSummary(Tier tier, long customers, long totalPoints) {}
List<TierSummary> rows = session.from(Customer.class)
        .select(col(Customer::getTier), count(), sum(Customer::getPoints))
        .groupBy(Customer::getTier)
        .into(TierSummary.class);
```

Selections: `col count countDistinct sum avg min max`. `scalar(Long.class)` returns one value.
Projections are not managed by the session.

## Fetch plans

```java
session.from(Order.class).fetch(Order::getCustomer).list();                       // LEFT JOIN, 1 statement
session.from(Customer.class).fetch(Customer::getOrders).list();                   // batch: 1 + 1 statements
session.from(Customer.class).fetch(Customer::getOrders, FetchMode.JOIN).list();   // join, rows deduplicated
```

Defaults: join for `@ManyToOne`, batch (`IN` list, chunked at 500) for `@OneToMany`. A collection
JOIN multiplies rows and is rejected together with `limit`/`offset`/`page`.

## Finding N+1 problems

`QueryCounter` and `NPlusOneDetector` are `SqlListener`s for tests:

```java
NPlusOneDetector detector = new NPlusOneDetector(3);
orm.addListener(detector);
renderOrderList(session);
detector.assertNone();     // fails if the same SELECT ran 3 or more times
```

Bound parameters keep the text of the N lazy loads identical, which is what the detector counts.

## Native SQL

```java
List<String> names = session.nativeQuery("select name from users where age >= ?", rs -> rs.getString(1), 18);
int changed = session.nativeUpdate("update users set active = false where last_seen < ?", cutoff);
```

Native updates do not tell the persistence context; refresh or clear afterwards.

## Repositories

```java
public interface UserRepository extends Repository<User, Long> {
    Optional<User> findByEmail(String email);
    List<User> findByStatusAndAgeGreaterThanOrderByCreatedAtDesc(Status status, int age);
    Page<User> findByLastNameContaining(String text, Pageable pageable);
    long countByStatus(Status status);
    boolean existsByEmail(String email);
    long deleteByStatus(Status status);
}
UserRepository users = session.repository(UserRepository.class);
```

Grammar: `find|read|get|query|count|exists|delete|remove`, optional `First`/`TopN`, then `By`,
properties joined by `And`/`Or` (`And` binds tighter), each with an optional suffix (`Not`,
`GreaterThan`, `GreaterThanEqual`, `LessThan`, `LessThanEqual`, `After`, `Before`, `Between`, `In`,
`NotIn`, `Like`, `Containing`, `StartingWith`, `EndingWith`, `IsNull`, `IsNotNull`, `True`,
`False`, `IgnoreCase`), then `OrderBy<Property>[Asc|Desc]...`. A trailing `Pageable` or `Sort`
parameter is allowed. Names are parsed when the repository is created, so a misspelt property
fails immediately and lists the known ones. Repositories are JDK dynamic proxies; an interface with
`default` methods must be public.
