# Comparison with Hibernate / JPA

mini-orm is a small, readable subset with a different design emphasis. It is not a replacement.

| Area | Hibernate / JPA | mini-orm |
| --- | --- | --- |
| Change detection | Snapshot comparison with bytecode enhancement as an option | Snapshot comparison only |
| Lazy loading | Proxies for entities, enhancement for attributes | Generated subclass proxy for `@ManyToOne`, lazy `List`/`Set` for `@OneToMany`; no lazy basic attributes |
| Query language | JPQL, Criteria, HQL | Typed fluent API on method references, projections to records, native SQL |
| Mapping | Inheritance, composite keys, `@ManyToMany`, `@OneToOne`, `@ElementCollection`, many id generators | Single table per entity, single-column keys, `@ManyToOne`/`@OneToMany(mappedBy)` |
| Cascading, orphan removal | Yes | No: persist and remove explicitly |
| Second-level and query cache | Yes | No (identity map per session only) |
| Joins in queries | Arbitrary | Fetch joins for one association; no navigation through associations in `where` |
| Pagination | Yes | Yes (`limit`, `offset`, `Page`) |
| Batching | Configurable | Always on, ordered by foreign-key dependency |
| Locking | Optimistic, pessimistic with scopes | `@Version`; `FOR UPDATE` and `NOWAIT` |
| Schema | `hbm2ddl`, Envers, many dialects | Create/drop/validate for PostgreSQL and H2; no migrations, no diff-and-alter |
| Inheritance strategies, multitenancy, filters, `@Formula`, events, interceptors | Yes | No |
| Dependencies | Many | None beyond the JDK |
| Transactions | JTA and resource-local | Resource-local, nested join, savepoints |
| Maturity | Two decades of production use | A portfolio-sized library with a test suite |

What mini-orm does not attempt: a JPA implementation, a query language parser, bidirectional
association synchronisation, lazy loading of arbitrary attributes, and databases other than
PostgreSQL and H2 (a `Dialect` can be supplied for another one).
