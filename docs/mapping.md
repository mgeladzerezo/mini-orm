# Mapping reference

Mapping annotations go on fields; accessors are never called by the ORM. Metadata is built once,
by reflection, when `MiniOrm.build()` runs, and is immutable afterwards.

| Annotation | Meaning |
| --- | --- |
| `@Entity` | A concrete, non-final class with a no-argument constructor (any visibility) and one `@Id`. |
| `@Table(name, indexes)` | Table name (default: snake_case of the class) and secondary indexes. |
| `@Id` | The primary key. Composite keys are not supported. |
| `@GeneratedValue` | `IDENTITY` (default; assigned by the INSERT), `SEQUENCE` (blocks of `allocationSize` ids per round trip; the sequence is created with that increment) or `UUID` (random, assigned at `persist`). |
| `@Column` | `name`, `nullable`, `length` (default 255, 0 means unbounded text), `unique`, `precision`/`scale` for `BigDecimal`, `updatable`. |
| `@Transient` | Excluded. `static` and `transient` fields are excluded automatically. |
| `@Enumerated` | `STRING` (default) or `ORDINAL`. |
| `@Version` | Optimistic lock: `int`, `long`, `Integer` or `Long`. |
| `@CreatedAt`, `@UpdatedAt` | Timestamps (`Instant`, `LocalDateTime`, `OffsetDateTime`) set at persist and at every UPDATE. |
| `@PrePersist`, `@PreUpdate` | No-argument instance methods called before the INSERT is queued, and before an UPDATE of a dirty entity. |
| `@ManyToOne(fetch)` | Foreign key column `<field>_id`. `LAZY` (default) puts a generated proxy in the field; `EAGER` loads the target with one `IN` query per result list. |
| `@OneToMany(mappedBy)` | Inverse side: a `List` or `Set` that loads lazily. It owns no column. |
| `@Embedded(prefix)` | A record or class whose fields become columns of the owner. A null object is stored as all-NULL columns. |
| `@Json` | Stored as text through the `JsonCodec` set on the builder. Dirty checking compares the serialised text. |
| `@Convert` | Use a specific `TypeConverter` for the field. |

## Types

`TypeConverter<J, D>` converts between a Java type and a JDBC type. Built in: primitives and
wrappers, `String`, `BigDecimal`, `BigInteger`, `UUID`, `byte[]`, enums, `LocalDate`, `LocalTime`,
`LocalDateTime`, `OffsetDateTime`, `Instant`, `ZonedDateTime`, `Duration`, `Character`, and
`Optional<T>` of any of them. Register your own with `builder().converter(Money.class, converter)`.

`Instant` is stored as `timestamp with time zone`, truncated to microseconds, which is what the
databases keep; otherwise a freshly persisted entity would differ from what a later SELECT returns.

## Records

Records are supported as embeddables (`@Embedded`) and as read-only projections
(`select(...).into(MyRecord.class)`). A record cannot be an entity because entities are mutable
and managed.

## Entity requirements for lazy loading

A class referenced by a lazy `@ManyToOne` must be non-final with a non-private no-argument
constructor. If it is not, the error at startup says so and suggests `EAGER`.
