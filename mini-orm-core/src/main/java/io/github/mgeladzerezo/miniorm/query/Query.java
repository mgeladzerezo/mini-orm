package io.github.mgeladzerezo.miniorm.query;

import io.github.mgeladzerezo.miniorm.LockMode;
import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.error.NonUniqueResultException;
import io.github.mgeladzerezo.miniorm.internal.EntitySql;
import io.github.mgeladzerezo.miniorm.internal.QueryContext;
import io.github.mgeladzerezo.miniorm.internal.RowReader;
import io.github.mgeladzerezo.miniorm.mapping.AttributeMapping;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.mapping.ManyToOneAttribute;
import io.github.mgeladzerezo.miniorm.mapping.OneToManyAttribute;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A type-safe query over one entity, built with a fluent API and executed by a terminal method.
 *
 * <pre>{@code
 * List<User> users = session.from(User.class)
 *         .where(User::getStatus, eq(Status.ACTIVE))
 *         .and(User::getAge, ge(18))
 *         .orderBy(Sort.desc(User::getCreatedAt))
 *         .limit(10)
 *         .list();
 * }</pre>
 *
 * <p>Properties are getter method references, resolved to columns without calling the getter.
 * Operands are bound as JDBC parameters; no value is ever concatenated into SQL. A pending
 * change in the session is flushed before the query runs (inside a transaction), so a query
 * sees the session's own writes.
 *
 * <p>{@code and}/{@code or} combine left to right: {@code a.and(b).or(c)} means
 * {@code (a and b) or c}. For other groupings build a {@link Condition}.
 *
 * @param <T> the entity type
 */
public final class Query<T> {

    private static final String ROOT = EntitySql.ROOT;

    private final QueryContext context;
    private final EntityMetadata<T> metadata;
    private final SqlRenderer renderer;
    private Expr where;
    private Sort sort = Sort.unsorted();
    private Integer limit;
    private Integer offset;
    private LockMode lockMode = LockMode.NONE;
    private final Map<String, FetchMode> fetches = new java.util.LinkedHashMap<>();

    /**
     * Created by {@code Session.from}; not meant to be called directly.
     *
     * @param context  the session's view
     * @param metadata the queried entity
     */
    public Query(QueryContext context, EntityMetadata<T> metadata) {
        this.context = context;
        this.metadata = metadata;
        this.renderer = new SqlRenderer(context.dialect(), metadata, ROOT);
    }

    // ------------------------------------------------------------------ building

    /**
     * Starts or extends the WHERE clause with AND.
     *
     * @param property getter reference or {@link Attribute}
     * @param criterion operator and operand, for example {@code eq("a@b.c")}
     * @param <V>      property type
     * @return this query
     */
    public <V> Query<T> where(Property<T, V> property, Criterion<V> criterion) {
        return and(property, criterion);
    }

    /**
     * Adds a condition with AND.
     *
     * @param condition a pre-built condition
     * @return this query
     */
    public Query<T> where(Condition<T> condition) {
        return and(condition);
    }

    /**
     * @param property  getter reference or {@link Attribute}
     * @param criterion operator and operand
     * @param <V>       property type
     * @return this query, with {@code AND property criterion} added
     */
    public <V> Query<T> and(Property<T, V> property, Criterion<V> criterion) {
        return and(Condition.where(property, criterion));
    }

    /**
     * @param condition a pre-built condition
     * @return this query, with {@code AND (condition)} added
     */
    public Query<T> and(Condition<T> condition) {
        where = Expr.and(where, condition.expr());
        return this;
    }

    /**
     * @param property  getter reference or {@link Attribute}
     * @param criterion operator and operand
     * @param <V>       property type
     * @return this query, with {@code OR property criterion} added
     */
    public <V> Query<T> or(Property<T, V> property, Criterion<V> criterion) {
        return or(Condition.where(property, criterion));
    }

    /**
     * @param condition a pre-built condition
     * @return this query, with {@code OR (condition)} added
     */
    public Query<T> or(Condition<T> condition) {
        where = Expr.or(where, condition.expr());
        return this;
    }

    /**
     * Sets the ordering, replacing an earlier one.
     *
     * @param sort for example {@code Sort.asc(User::getName).then(Sort.desc(User::getId))}
     * @return this query
     */
    public Query<T> orderBy(Sort sort) {
        this.sort = Objects.requireNonNull(sort, "sort");
        return this;
    }

    /**
     * @param maxRows at most this many rows are returned
     * @return this query
     */
    public Query<T> limit(int maxRows) {
        if (maxRows < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        this.limit = maxRows;
        return this;
    }

    /**
     * @param skipped number of rows to skip; without an ordering the result is arbitrary
     * @return this query
     */
    public Query<T> offset(int skipped) {
        if (skipped < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        this.offset = skipped;
        return this;
    }

    /**
     * Loads an association together with the result to avoid one query per row. A
     * {@code @ManyToOne} is fetched with a LEFT JOIN, a {@code @OneToMany} with one extra
     * {@code IN} query; see {@link #fetch(Property, FetchMode)} to choose.
     *
     * @param association getter reference of the association
     * @return this query
     */
    public Query<T> fetch(Property<T, ?> association) {
        String name = PropertyNames.of(association);
        return fetch(association, metadata.attribute(name) instanceof OneToManyAttribute
                ? FetchMode.BATCH : FetchMode.JOIN);
    }

    /**
     * Loads an association together with the result using the given strategy.
     *
     * @param association getter reference of the association
     * @param mode        JOIN (one query) or BATCH (one extra query with IN)
     * @return this query
     * @throws MappingException if the property is not a {@code @ManyToOne} or {@code @OneToMany}
     */
    public Query<T> fetch(Property<T, ?> association, FetchMode mode) {
        String name = PropertyNames.of(association);
        AttributeMapping attribute = metadata.attribute(name);
        if (!(attribute instanceof ManyToOneAttribute) && !(attribute instanceof OneToManyAttribute)) {
            throw new MappingException(metadata.entityName() + "." + name + " is not an association");
        }
        fetches.put(name, mode);
        return this;
    }

    /**
     * Locks the selected rows ({@code SELECT ... FOR UPDATE}). Needs a transaction.
     *
     * @param mode the lock to take
     * @return this query
     */
    public Query<T> lock(LockMode mode) {
        this.lockMode = context.checkLock(mode);
        return this;
    }

    // ------------------------------------------------------------------ terminal operations

    /**
     * Runs the query.
     *
     * @return the matching entities, managed by the session, in query order
     */
    public List<T> list() {
        return execute(sort, limit, offset);
    }

    /**
     * @return the first matching entity, if any (applies {@code limit 1})
     */
    public Optional<T> first() {
        List<T> rows = execute(sort, 1, offset);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * @return the only matching entity, or empty if none matches
     * @throws NonUniqueResultException if more than one row matches
     */
    public Optional<T> one() {
        List<T> rows = execute(sort, 2, offset);
        if (rows.size() > 1) {
            throw new NonUniqueResultException("Expected at most one " + metadata.entityName() + " but the query"
                    + " matched several");
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * @return the number of matching rows, ignoring limit, offset and fetches
     */
    public long count() {
        context.autoFlush();
        List<Object> parameters = new ArrayList<>();
        String sql = "select count(*) from " + table() + " " + ROOT + whereClause(parameters);
        return context.query(sql, parameters, rs -> {
            rs.next();
            return rs.getLong(1);
        });
    }

    /**
     * @return whether at least one row matches; stops at the first
     */
    public boolean exists() {
        context.autoFlush();
        List<Object> parameters = new ArrayList<>();
        String sql = context.dialect().paginate("select 1 from " + table() + " " + ROOT + whereClause(parameters),
                1, null, parameters);
        return context.query(sql, parameters, rs -> rs.next());
    }

    /**
     * Returns one page and the total row count (two statements: the page, then {@code count(*)}).
     * When the pageable has no sort the page is ordered by primary key so that pages are stable.
     *
     * @param pageable page index, size and optional sort; replaces this query's limit, offset and sort
     * @return the page
     */
    public Page<T> page(Pageable pageable) {
        Sort ordering = pageable.sort().isSorted() ? pageable.sort() : this.sort;
        if (!ordering.isSorted()) {
            ordering = Sort.by(metadata.idColumn().property(), Sort.Direction.ASC);
        }
        List<T> content = execute(ordering, pageable.size(), (int) pageable.offset());
        return new Page<>(content, pageable.page(), pageable.size(), count());
    }

    /**
     * Switches to a projection: only the listed columns and aggregates are selected, and the
     * result is not made of entities (nothing becomes managed). A selected {@code @ManyToOne} yields the foreign key value.
     *
     * @param selections columns and aggregates, see {@link Selections}
     * @return a projection that can be mapped to records
     */
    @SafeVarargs
    public final Projection<T> select(Selection<T>... selections) {
        return new Projection<>(context, metadata, renderer, where, sort, limit, offset, List.of(selections));
    }

    // ------------------------------------------------------------------ execution

    private String table() {
        return context.dialect().quote(metadata.tableName());
    }

    private String whereClause(List<Object> parameters) {
        return where == null ? "" : " where " + renderer.where(where, parameters);
    }

    /** A fetch join: where its columns start in the select list, and how it attaches to the root. */
    private record Join(String alias, AttributeMapping attribute, EntityMetadata<?> target, int firstColumn) {
    }

    private List<T> execute(Sort ordering, Integer maxRows, Integer skipped) {
        context.autoFlush();
        EntitySql rootSql = context.sql(metadata);
        List<Join> joins = new ArrayList<>();
        StringBuilder select = new StringBuilder("select ").append(rootSql.selectList(ROOT));
        StringBuilder from = new StringBuilder(" from ").append(table()).append(' ').append(ROOT);
        int nextColumn = 1 + metadata.columns().size();
        List<Map.Entry<String, FetchMode>> batchFetches = new ArrayList<>();
        for (Map.Entry<String, FetchMode> fetch : fetches.entrySet()) {
            AttributeMapping attribute = metadata.attribute(fetch.getKey());
            if (fetch.getValue() == FetchMode.BATCH) {
                batchFetches.add(fetch);
                continue;
            }
            if (attribute instanceof OneToManyAttribute && (maxRows != null || skipped != null)) {
                throw new IllegalStateException("fetch(" + fetch.getKey() + ", JOIN) multiplies rows and cannot be"
                        + " combined with limit/offset/page; use FetchMode.BATCH");
            }
            String alias = "t" + (joins.size() + 1);
            EntityMetadata<?> target;
            String on;
            if (attribute instanceof ManyToOneAttribute toOne) {
                target = toOne.target();
                on = alias + "." + context.dialect().quote(target.idColumn().name()) + " = " + ROOT + "."
                        + context.dialect().quote(toOne.column().name());
            } else {
                OneToManyAttribute toMany = (OneToManyAttribute) attribute;
                target = toMany.target();
                on = alias + "." + context.dialect().quote(toMany.inverse().column().name()) + " = " + ROOT + "."
                        + context.dialect().quote(metadata.idColumn().name());
            }
            EntitySql targetSql = context.sql(target);
            select.append(", ").append(targetSql.selectList(alias));
            from.append(" left join ").append(context.dialect().quote(target.tableName())).append(' ').append(alias)
                    .append(" on ").append(on);
            joins.add(new Join(alias, attribute, target, nextColumn));
            nextColumn += target.columns().size();
        }
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder(select).append(from).append(whereClause(parameters));
        if (ordering.isSorted()) {
            sql.append(" order by ").append(renderer.orderBy(ordering.orders()));
        }
        String statement = context.dialect().paginate(sql.toString(), maxRows, skipped, parameters)
                + context.dialect().lockClause(lockMode);

        List<T> result = context.query(statement, parameters, rs -> readRows(rs, joins));
        context.afterLoad();
        for (Map.Entry<String, FetchMode> fetch : batchFetches) {
            AttributeMapping attribute = metadata.attribute(fetch.getKey());
            if (attribute instanceof ManyToOneAttribute toOne) {
                context.batchLoadReferences(result, toOne);
            } else {
                context.batchLoadCollections(result, (OneToManyAttribute) attribute);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<T> readRows(java.sql.ResultSet rs, List<Join> joins) throws java.sql.SQLException {
        List<T> roots = new ArrayList<>();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        Map<Join, Map<Object, List<Object>>> collections = new IdentityHashMap<>();
        while (rs.next()) {
            T root = (T) context.materialize(metadata, RowReader.read(rs, metadata, 1));
            if (seen.put(root, Boolean.TRUE) == null) {
                roots.add(root);
            }
            for (Join join : joins) {
                Object[] row = RowReader.read(rs, join.target(), join.firstColumn());
                Object child = row[join.target().idColumn().index()] == null ? null
                        : context.materialize(join.target(), row);
                if (join.attribute() instanceof ManyToOneAttribute toOne) {
                    if (child != null) {
                        context.replaceReference(root, toOne, child);
                    }
                } else {
                    List<Object> elements = collections.computeIfAbsent(join, j -> new IdentityHashMap<>())
                            .computeIfAbsent(root, r -> new ArrayList<>());
                    if (child != null) {
                        elements.add(child);
                    }
                }
            }
        }
        for (Join join : joins) {
            if (join.attribute() instanceof OneToManyAttribute toMany) {
                Map<Object, List<Object>> byOwner = collections.getOrDefault(join, Map.of());
                for (T root : roots) {
                    context.initializeCollection(root, toMany, byOwner.getOrDefault(root, List.of()));
                }
            }
        }
        return roots;
    }
}
