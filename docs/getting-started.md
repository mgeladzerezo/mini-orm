# Getting started

mini-orm needs Java 25, a JDBC driver (PostgreSQL or H2) and nothing else at run time.

## Dependencies

```xml
<dependency>
  <groupId>io.github.mgeladzerezo</groupId>
  <artifactId>mini-orm-core</artifactId>
  <version>0.1.0</version>
</dependency>
<!-- optional: the connection pool, which also works on its own -->
<dependency>
  <groupId>io.github.mgeladzerezo</groupId>
  <artifactId>mini-orm-pool</artifactId>
  <version>0.1.0</version>
</dependency>
```

The artifacts are published to GitHub Packages by the `release.yml` workflow when a `v*` tag is pushed.

## A first program

```java
@Entity
public class User {
    @Id @GeneratedValue private Long id;
    @Column(unique = true, nullable = false) private String email;
    @Version private long version;
    protected User() {}                        // required: package-private or protected is fine
    public User(String email) { this.email = email; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}

MiniPool pool = MiniPool.create("jdbc:postgresql://localhost/app", "app", "secret");
MiniOrm orm = MiniOrm.builder().dataSource(pool).entities(User.class).build();
orm.schema().create();                         // tests and first deployments only

orm.runInTransaction(session -> session.persist(new User("ada@example.com")));

orm.runInTransaction(session -> {
    User user = session.from(User.class).where(User::getEmail, eq("ada@example.com")).one().orElseThrow();
    user.setEmail("ada@example.org");          // no save call: the change is detected at commit
});
```

## What to know first

* Writes need a transaction (`session.inTransaction`, `orm.runInTransaction`). Reads do not.
* A `Session` is not thread-safe. Open one per request or per unit of work.
* There is no cascading. Persist the entities you reference.
* Mapping errors are reported when `build()` runs, with the class and field named.

If your application is a named module, open the packages that contain entities to
`io.github.mgeladzerezo.miniorm.core`, because mapping uses reflection and lazy proxies are
defined in the entity's package.

Next: [mapping](mapping.md), [sessions](sessions.md), [querying](querying.md), [the pool](pool.md).
