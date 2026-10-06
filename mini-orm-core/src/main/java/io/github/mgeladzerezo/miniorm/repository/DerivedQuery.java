package io.github.mgeladzerezo.miniorm.repository;

import static io.github.mgeladzerezo.miniorm.query.Criteria.between;
import static io.github.mgeladzerezo.miniorm.query.Criteria.contains;
import static io.github.mgeladzerezo.miniorm.query.Criteria.containsIgnoreCase;
import static io.github.mgeladzerezo.miniorm.query.Criteria.endsWith;
import static io.github.mgeladzerezo.miniorm.query.Criteria.eq;
import static io.github.mgeladzerezo.miniorm.query.Criteria.escapeLike;
import static io.github.mgeladzerezo.miniorm.query.Criteria.ilike;
import static io.github.mgeladzerezo.miniorm.query.Criteria.isNotNull;
import static io.github.mgeladzerezo.miniorm.query.Criteria.isNull;
import static io.github.mgeladzerezo.miniorm.query.Criteria.like;
import static io.github.mgeladzerezo.miniorm.query.Criteria.ne;
import static io.github.mgeladzerezo.miniorm.query.Criteria.notIn;
import static io.github.mgeladzerezo.miniorm.query.Criteria.startsWith;

import io.github.mgeladzerezo.miniorm.Session;
import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.mapping.ColumnMapping;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.query.Condition;
import io.github.mgeladzerezo.miniorm.query.Criteria;
import io.github.mgeladzerezo.miniorm.query.Criterion;
import io.github.mgeladzerezo.miniorm.query.Page;
import io.github.mgeladzerezo.miniorm.query.Pageable;
import io.github.mgeladzerezo.miniorm.query.Query;
import io.github.mgeladzerezo.miniorm.query.Sort;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repository method whose behaviour is read from its name, parsed once when the repository is
 * created so that a misspelled property fails immediately and not at the first call.
 *
 * <p>Grammar: {@code <subject>[Distinct][First|TopN]By<criteria>[OrderBy<property>[Asc|Desc]...]}.
 * Subjects are {@code find/read/get/query}, {@code count}, {@code exists} and {@code delete/remove}.
 * Criteria are properties joined by {@code And} and {@code Or} ({@code And} binds tighter), each
 * with an optional operator suffix: {@code Not}, {@code GreaterThan}, {@code GreaterThanEqual},
 * {@code LessThan}, {@code LessThanEqual}, {@code After}, {@code Before}, {@code Between},
 * {@code In}, {@code NotIn}, {@code Like}, {@code Containing},
 * {@code StartingWith}, {@code EndingWith}, {@code IsNull}, {@code IsNotNull}, {@code True},
 * {@code False}, and {@code IgnoreCase} on string comparisons.
 */
final class DerivedQuery {

    private enum Subject { FIND, COUNT, EXISTS, DELETE }

    private enum Operator {
        EQ(1), NE(1), GT(1), GE(1), LT(1), LE(1), BETWEEN(2), IN(1), NOT_IN(1), LIKE(1), CONTAINING(1),
        STARTING_WITH(1), ENDING_WITH(1), IS_NULL(0), IS_NOT_NULL(0), TRUE(0), FALSE(0);

        final int arguments;

        Operator(int arguments) {
            this.arguments = arguments;
        }
    }

    /** Operator suffixes, longest first so that {@code GreaterThanEqual} wins over {@code Equal}-less forms. */
    private static final List<Map.Entry<String, Operator>> SUFFIXES = suffixes();
    private static final Pattern NAME = Pattern.compile(
            "^(find|read|get|query|count|exists|delete|remove)(Distinct)?(?:(First|Top(\\d+)))?By(.*)$");
    private static final Pattern AND = Pattern.compile("(?<=[a-z0-9])And(?=[A-Z])");
    private static final Pattern OR = Pattern.compile("(?<=[a-z0-9])Or(?=[A-Z])");
    private static final Pattern ORDER_SEGMENT = Pattern.compile("(?<=Asc|Desc)(?=[A-Z])");

    /** One comparison: a property, an operator and whether strings compare case-insensitively. */
    private record Part(String property, Operator operator, boolean ignoreCase, int firstArgument) {
    }

    private final Method method;
    private final Class<?> entityClass;
    private final Subject subject;
    private final List<List<Part>> orGroups;
    private final List<Sort.Order> orders;
    private final Integer limit;
    private final int criteriaArguments;
    private final int pageableIndex;
    private final int sortIndex;

    private DerivedQuery(Method method, Class<?> entityClass, Subject subject, List<List<Part>> orGroups,
                         List<Sort.Order> orders, Integer limit, int criteriaArguments) {
        this.method = method;
        this.entityClass = entityClass;
        this.subject = subject;
        this.orGroups = orGroups;
        this.orders = orders;
        this.limit = limit;
        this.criteriaArguments = criteriaArguments;
        int pageable = -1;
        int sort = -1;
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = criteriaArguments; i < parameters.length; i++) {
            if (parameters[i] == Pageable.class) {
                pageable = i;
            } else if (parameters[i] == Sort.class) {
                sort = i;
            } else {
                throw new MappingException(describe(method) + ": the name consumes " + criteriaArguments
                        + " argument(s); the extra parameter " + parameters[i].getSimpleName()
                        + " must be a Pageable or a Sort");
            }
        }
        this.pageableIndex = pageable;
        this.sortIndex = sort;
    }

    // ------------------------------------------------------------------ parsing

    static DerivedQuery parse(Method method, EntityMetadata<?> metadata) {
        Matcher matcher = NAME.matcher(method.getName());
        if (!matcher.matches()) {
            throw new MappingException(describe(method) + " is neither a method of Repository nor a derived finder."
                    + " Expected a name like findByEmail, countByStatus, existsByEmail or deleteByStatus");
        }
        Subject subject = switch (matcher.group(1)) {
            case "count" -> Subject.COUNT;
            case "exists" -> Subject.EXISTS;
            case "delete", "remove" -> Subject.DELETE;
            default -> Subject.FIND;
        };
        Integer limit = matcher.group(3) == null ? null : matcher.group(4) == null ? 1 : Integer.valueOf(matcher.group(4));
        String rest = matcher.group(5);
        String criteria = rest;
        String ordering = "";
        int orderBy = rest.indexOf("OrderBy");
        if (orderBy >= 0) {
            criteria = rest.substring(0, orderBy);
            ordering = rest.substring(orderBy + "OrderBy".length());
        }
        Map<String, String> properties = propertyKeys(metadata);
        List<List<Part>> groups = new ArrayList<>();
        int argument = 0;
        if (!criteria.isEmpty()) {
            for (String orPart : OR.split(criteria)) {
                List<Part> group = new ArrayList<>();
                for (String andPart : AND.split(orPart)) {
                    Part part = parsePart(method, andPart, properties, argument);
                    argument += part.operator.arguments;
                    group.add(part);
                }
                groups.add(group);
            }
        }
        List<Sort.Order> orders = parseOrder(method, ordering, properties);
        DerivedQuery query = new DerivedQuery(method, metadata.entityClass(), subject, groups, orders, limit, argument);
        query.validateReturnType();
        return query;
    }

    /** Maps {@code AddressCity} to {@code address.city}, {@code Email} to {@code email}, for every persistent property. */
    private static Map<String, String> propertyKeys(EntityMetadata<?> metadata) {
        Map<String, String> keys = new HashMap<>();
        for (ColumnMapping column : metadata.columns()) {
            StringBuilder key = new StringBuilder();
            for (String segment : column.property().split("\\.")) {
                key.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
            }
            keys.put(key.toString(), column.property());
        }
        return keys;
    }

    private static Part parsePart(Method method, String text, Map<String, String> properties, int firstArgument) {
        String remaining = text;
        boolean ignoreCase = false;
        if (remaining.endsWith("IgnoreCase")) {
            ignoreCase = true;
            remaining = remaining.substring(0, remaining.length() - "IgnoreCase".length());
        }
        Operator operator = Operator.EQ;
        // An exact property name wins over a suffix that happens to be part of it (a property called "Before").
        if (!properties.containsKey(remaining)) {
            for (Map.Entry<String, Operator> suffix : SUFFIXES) {
                if (remaining.endsWith(suffix.getKey()) && properties.containsKey(
                        remaining.substring(0, remaining.length() - suffix.getKey().length()))) {
                    operator = suffix.getValue();
                    remaining = remaining.substring(0, remaining.length() - suffix.getKey().length());
                    break;
                }
            }
        }
        String property = properties.get(remaining);
        if (property == null) {
            throw new MappingException(describe(method) + ": '" + text + "' does not name a persistent property of the"
                    + " entity (with an optional operator suffix). Known: " + properties.keySet().stream().sorted().toList());
        }
        return new Part(property, operator, ignoreCase, firstArgument);
    }

    private static List<Sort.Order> parseOrder(Method method, String ordering, Map<String, String> properties) {
        List<Sort.Order> orders = new ArrayList<>();
        if (ordering.isEmpty()) {
            return orders;
        }
        for (String segment : ORDER_SEGMENT.split(ordering)) {
            Sort.Direction direction = Sort.Direction.ASC;
            String name = segment;
            if (segment.endsWith("Desc")) {
                direction = Sort.Direction.DESC;
                name = segment.substring(0, segment.length() - 4);
            } else if (segment.endsWith("Asc")) {
                name = segment.substring(0, segment.length() - 3);
            }
            String property = properties.get(name);
            if (property == null) {
                throw new MappingException(describe(method) + ": OrderBy refers to unknown property '" + name + "'");
            }
            orders.add(new Sort.Order(property, direction));
        }
        return orders;
    }

    private static List<Map.Entry<String, Operator>> suffixes() {
        List<Map.Entry<String, Operator>> list = new ArrayList<>(List.of(
                Map.entry("IsNotNull", Operator.IS_NOT_NULL), Map.entry("NotNull", Operator.IS_NOT_NULL),
                Map.entry("IsNull", Operator.IS_NULL), Map.entry("Null", Operator.IS_NULL),
                Map.entry("GreaterThanEqual", Operator.GE), Map.entry("GreaterThan", Operator.GT),
                Map.entry("LessThanEqual", Operator.LE), Map.entry("LessThan", Operator.LT),
                Map.entry("After", Operator.GT), Map.entry("Before", Operator.LT),
                Map.entry("Between", Operator.BETWEEN), Map.entry("NotIn", Operator.NOT_IN),
                Map.entry("In", Operator.IN), Map.entry("Like", Operator.LIKE),
                Map.entry("Containing", Operator.CONTAINING), Map.entry("Contains", Operator.CONTAINING),
                Map.entry("StartingWith", Operator.STARTING_WITH), Map.entry("StartsWith", Operator.STARTING_WITH),
                Map.entry("EndingWith", Operator.ENDING_WITH), Map.entry("EndsWith", Operator.ENDING_WITH),
                Map.entry("True", Operator.TRUE), Map.entry("False", Operator.FALSE),
                Map.entry("Not", Operator.NE), Map.entry("Is", Operator.EQ), Map.entry("Equals", Operator.EQ)));
        list.sort(Comparator.comparingInt((Map.Entry<String, Operator> e) -> e.getKey().length()).reversed());
        return list;
    }

    private void validateReturnType() {
        Class<?> returned = method.getReturnType();
        boolean ok = switch (subject) {
            case COUNT -> returned == long.class || returned == Long.class || returned == int.class;
            case EXISTS -> returned == boolean.class || returned == Boolean.class;
            case DELETE -> returned == void.class || returned == long.class || returned == int.class;
            case FIND -> returned == Optional.class || Collection.class.isAssignableFrom(returned)
                    || returned == Page.class || returned.isAssignableFrom(entityClass);
        };
        if (!ok) {
            throw new MappingException(describe(method) + " returns " + returned.getSimpleName()
                    + ", which does not fit a '" + subject.name().toLowerCase(Locale.ROOT) + "' method");
        }
        if (returned == Page.class && pageableIndex < 0) {
            throw new MappingException(describe(method) + " returns a Page and needs a Pageable parameter");
        }
        if (method.getParameterCount() < criteriaArguments) {
            throw new MappingException(describe(method) + " needs " + criteriaArguments + " parameter(s) for its"
                    + " criteria but declares " + method.getParameterCount());
        }
        if (pageableIndex >= 0 && returned != Page.class && !Collection.class.isAssignableFrom(returned)) {
            throw new MappingException(describe(method) + ": a Pageable parameter requires a Page or List result");
        }
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    // ------------------------------------------------------------------ execution

    @SuppressWarnings("unchecked")
    Object execute(Session session, Object[] arguments) {
        Query<Object> query = session.from((Class<Object>) entityClass);
        Condition<Object> condition = null;
        for (List<Part> group : orGroups) {
            Condition<Object> and = null;
            for (Part part : group) {
                Condition<Object> leaf = Condition.ofProperty(part.property, criterion(part, arguments));
                and = and == null ? leaf : and.and(leaf);
            }
            condition = condition == null ? and : condition.or(and);
        }
        if (condition != null) {
            query.where(condition);
        }
        Sort sort = Sort.unsorted();
        if (!orders.isEmpty()) {
            sort = new Sort(orders);
        }
        if (sortIndex >= 0 && arguments[sortIndex] != null) {
            sort = (Sort) arguments[sortIndex];
        }
        switch (subject) {
            case COUNT -> {
                long count = query.count();
                return method.getReturnType() == int.class ? (Object) Math.toIntExact(count) : (Object) count;
            }
            case EXISTS -> {
                return query.exists();
            }
            case DELETE -> {
                List<Object> matches = query.list();
                matches.forEach(session::remove);
                return switch (method.getReturnType().getName()) {
                    case "int" -> (Object) matches.size();
                    case "long" -> (Object) (long) matches.size();
                    default -> null;
                };
            }
            default -> {
                return find(query, sort, arguments);
            }
        }
    }

    private Object find(Query<Object> query, Sort sort, Object[] arguments) {
        Class<?> returned = method.getReturnType();
        if (returned == Page.class) {
            Pageable pageable = (Pageable) arguments[pageableIndex];
            Sort effective = pageable.sort().isSorted() ? pageable.sort() : sort;
            return query.page(Pageable.of(pageable.page(), pageable.size(), effective));
        }
        query.orderBy(sort);
        if (pageableIndex >= 0) {
            Pageable pageable = (Pageable) arguments[pageableIndex];
            if (pageable.sort().isSorted()) {
                query.orderBy(pageable.sort());
            }
            return query.offset((int) pageable.offset()).limit(pageable.size()).list();
        }
        if (Collection.class.isAssignableFrom(returned)) {
            if (limit != null) {
                query.limit(limit);
            }
            return query.list();
        }
        boolean optional = returned == Optional.class;
        Optional<Object> result = limit != null ? query.first() : query.one();
        return optional ? result : result.orElse(null);
    }

    private Criterion<Object> criterion(Part part, Object[] arguments) {
        Object first = part.operator.arguments > 0 ? arguments[part.firstArgument] : null;
        return switch (part.operator) {
            case EQ -> part.ignoreCase && first instanceof String text ? cast(ilike(escapeLike(text))) : cast(eq(first));
            case NE -> cast(ne(first));
            case GT -> cast(Criteria.gt(comparable(first)));
            case GE -> cast(Criteria.ge(comparable(first)));
            case LT -> cast(Criteria.lt(comparable(first)));
            case LE -> cast(Criteria.le(comparable(first)));
            case BETWEEN -> cast(between(comparable(first), comparable(arguments[part.firstArgument + 1])));
            case IN -> cast(Criteria.in((Collection<?>) first));
            case NOT_IN -> cast(notIn((Collection<?>) first));
            case LIKE -> cast(part.ignoreCase ? ilike((String) first) : like((String) first));
            case CONTAINING -> cast(part.ignoreCase ? containsIgnoreCase((String) first) : contains((String) first));
            case STARTING_WITH -> cast(part.ignoreCase ? ilike(escapeLike((String) first) + "%")
                    : startsWith((String) first));
            case ENDING_WITH -> cast(part.ignoreCase ? ilike("%" + escapeLike((String) first))
                    : endsWith((String) first));
            case IS_NULL -> cast(isNull());
            case IS_NOT_NULL -> cast(isNotNull());
            case TRUE -> cast(eq(Boolean.TRUE));
            case FALSE -> cast(eq(Boolean.FALSE));
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparable comparable(Object value) {
        return (Comparable) value;
    }

    @SuppressWarnings("unchecked")
    private static Criterion<Object> cast(Criterion<?> criterion) {
        return (Criterion<Object>) criterion;
    }
}
