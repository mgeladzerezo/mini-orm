# How it works inside

## Layout

```
mini-orm-pool                     DataSource: MiniPool, PooledConnection (proxy), housekeeping, metrics
mini-orm-core
  annotation/                     @Entity, @Id, ... (public API)
  mapping/                        MetadataBuilder (reflection, once) -> EntityMetadata, ColumnMapping, attributes
  type/                           TypeConverter, TypeRegistry, JsonCodec
  dialect/                        Dialect interface, PostgresDialect, H2Dialect
  internal/                       PersistenceContext, Flusher, TopologicalSort, StatementExecutor, ProxyFactory, ...
  query/                          Property/Attribute, Criteria, Expr tree, SqlRenderer, Query, Projection
  repository/                     Repository, derived finder parser, dynamic proxy factory
  schema/                         SchemaManager: DDL generation and validation
  sql/                            SqlListener, SqlLogger, QueryCounter, NPlusOneDetector, SqlRecorder
  Session, MiniOrm, Transaction   the public entry points
```

## Metadata

Reflection happens once. `MetadataBuilder` flattens an entity into an ordered list of
`ColumnMapping`s; an embedded record contributes one column per component, a `@ManyToOne` one
foreign-key column that takes its type and size from the target's id column. Everything else (SQL
generation, snapshots, dirty checks, schema) works on that column list, so an entity's state is
just an `Object[]` of database-side values.

## Dirty checking

See [sessions](sessions.md). The snapshot is taken from the finished instance, not from the raw
result row, so a converter that normalises a value cannot make an entity look modified.

## Flush ordering

`Flusher` builds a dependency list for new entities (each `@ManyToOne` target that is itself new)
and runs `TopologicalSort` (Kahn's algorithm with a priority queue). The tie-break is "type rank,
then persist order". Type rank is the entity's position in the foreign-key order of the whole
model, computed once. Without the tie-break a valid topological order could interleave tables and
defeat batching. Cycles among new rows raise a descriptive error.

## Lazy proxies: `java.lang.classfile`

A lazy `@ManyToOne` needs an object that can stand in for an entity. Options: a `Lazy<T>` holder
(changes the entity's API), a JDK proxy (entities are classes, not interfaces), or a generated
subclass. mini-orm generates the subclass with the `java.lang.classfile` API (final in Java 24, so
no ASM dependency). `ProxyFactory` emits `Entity$MiniOrmProxy extends Entity implements EntityProxy`
with one forwarding override per overridable method; each fetches the target from a
`LazyInitializer` and calls the same method on it. The id getter is answered from the initializer
without a query. The class is defined with a private lookup in the entity's own package, so
package-private and protected methods can be overridden and no agent or `--add-opens` is needed.
Limitations: the entity must be non-final with a non-private no-argument constructor; `final`
methods and direct field access on a proxy are not intercepted; `equals` on a proxy is identity
unless the entity overrides it.

## Statement execution

Every statement goes through `StatementExecutor`: values can only be bound as parameters, each
round trip is timed and reported to the `SqlListener`s, and `SQLException`s are translated
(`ConstraintViolationException` for SQLState class 23, `PessimisticLockException` for lock
failures, otherwise `PersistenceException` carrying the SQL).

## Sequences

`SequenceAllocator` reserves `allocationSize` ids per `nextval`, with the sequence created
`increment by allocationSize`. Ids are unique across JVMs; gaps appear on restart.

## Dialects

`Dialect` covers identifier quoting, DDL type names, pagination, lock clauses, sequence access,
upsert, how to read generated keys and how to recognise a lock failure. Everything else is
standard SQL.
