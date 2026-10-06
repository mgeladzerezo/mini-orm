package io.github.mgeladzerezo.miniorm.type;

import io.github.mgeladzerezo.miniorm.error.MappingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Maps Java types to {@link TypeConverter}s. A registry starts with converters for the JDK
 * types listed in the mapping reference and accepts user converters through
 * {@link #register}.
 *
 * <p>The generic signatures do the bookkeeping: {@code register(Class<J>, TypeConverter<J, ?>)}
 * will not compile if the converter is for a different Java type, and
 * {@link #converterFor(Class)} returns a converter typed to the class it was asked about.
 */
public final class TypeRegistry {

    private static final Set<Class<?>> DATABASE_TYPES = Set.of(
            Boolean.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
            BigDecimal.class, String.class, byte[].class, LocalDate.class, LocalTime.class,
            LocalDateTime.class, OffsetDateTime.class, UUID.class);

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class,
            char.class, Character.class, int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class);

    private final Map<Class<?>, TypeConverter<?, ?>> converters = new ConcurrentHashMap<>();

    /** Creates a registry holding the built-in converters. */
    public TypeRegistry() {
        for (Class<?> type : DATABASE_TYPES) {
            registerIdentity(type);
        }
        put(Byte.class, Short.class, Byte::shortValue, Short::byteValue);
        put(Character.class, String.class, String::valueOf, s -> s.charAt(0));
        put(BigInteger.class, BigDecimal.class, BigDecimal::new, BigDecimal::toBigIntegerExact);
        // Databases keep microseconds; truncating on the way in keeps the in-memory snapshot
        // equal to what a later SELECT returns, so a clock with finer resolution does not
        // make every freshly persisted entity look dirty.
        put(Instant.class, OffsetDateTime.class,
                i -> i.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC), OffsetDateTime::toInstant);
        put(ZonedDateTime.class, OffsetDateTime.class,
                z -> z.toOffsetDateTime().truncatedTo(ChronoUnit.MICROS), OffsetDateTime::toZonedDateTime);
        put(Duration.class, Long.class, Duration::toNanos, Duration::ofNanos);
    }

    private <T> void registerIdentity(Class<T> type) {
        converters.put(type, TypeConverter.of(type, Function.<T>identity(), Function.<T>identity()));
    }

    private <J, D> void put(Class<J> javaType, Class<D> databaseType, Function<J, D> to, Function<D, J> from) {
        converters.put(javaType, TypeConverter.of(databaseType, to, from));
    }

    /**
     * The types a converter may use on the database side. Each one has a defined JDBC binding
     * and a DDL type in every dialect.
     *
     * @return the supported database-side types
     */
    public static Set<Class<?>> databaseTypes() {
        return DATABASE_TYPES;
    }

    /**
     * Registers (or replaces) the converter for a Java type.
     *
     * @param javaType  the field type to convert
     * @param converter the converter; its database type must be a supported one
     * @param <J>       the Java type
     * @return this registry
     * @throws MappingException if the converter targets an unsupported database type
     */
    public <J> TypeRegistry register(Class<J> javaType, TypeConverter<J, ?> converter) {
        Objects.requireNonNull(javaType, "javaType");
        validate(converter, javaType.getName());
        converters.put(javaType, converter);
        return this;
    }

    /**
     * Checks that a converter targets a database type the JDBC layer can bind.
     *
     * @param converter the converter to check
     * @param owner     what the converter is for, used in the error message
     * @throws MappingException if it does not
     */
    public static void validate(TypeConverter<?, ?> converter, String owner) {
        Objects.requireNonNull(converter, "converter");
        if (!DATABASE_TYPES.contains(converter.databaseType())) {
            throw new MappingException("Converter for " + owner + " targets " + converter.databaseType().getName()
                    + ", which is not a supported database type. Supported: " + DATABASE_TYPES.stream()
                    .map(Class::getSimpleName).sorted().toList());
        }
    }

    /**
     * Whether a field of this type can be mapped as a basic column.
     *
     * @param javaType a field type; primitives count as their wrappers
     * @return {@code true} for enums and for every registered type
     */
    public boolean supports(Class<?> javaType) {
        Class<?> boxed = box(javaType);
        return boxed.isEnum() || converters.containsKey(boxed);
    }

    /**
     * Finds the converter for a Java type. Enums get a by-name converter.
     *
     * @param javaType a field type; primitives are treated as their wrappers
     * @param <J>      the Java type
     * @return its converter
     * @throws MappingException if no converter is registered
     */
    @SuppressWarnings("unchecked")
    public <J> TypeConverter<J, ?> converterFor(Class<J> javaType) {
        Class<?> boxed = box(javaType);
        TypeConverter<?, ?> converter = converters.get(boxed);
        if (converter == null && boxed.isEnum()) {
            converter = enumByName(boxed.asSubclass(Enum.class));
        }
        if (converter == null) {
            throw new MappingException("No TypeConverter registered for " + javaType.getName()
                    + ". Register one with TypeRegistry.register, or annotate the field with @Convert, @Json,"
                    + " @Embedded, @ManyToOne or @Transient.");
        }
        return (TypeConverter<J, ?>) converter;
    }

    /**
     * A converter storing an enum by {@code name()}.
     *
     * @param type the enum class
     * @param <E>  the enum type
     * @return the converter
     */
    public static <E extends Enum<E>> TypeConverter<E, String> enumByName(Class<E> type) {
        return TypeConverter.of(String.class, Enum::name, name -> Enum.valueOf(type, name));
    }

    /**
     * A converter storing an enum by {@code ordinal()}.
     *
     * @param type the enum class
     * @param <E>  the enum type
     * @return the converter
     */
    public static <E extends Enum<E>> TypeConverter<E, Integer> enumByOrdinal(Class<E> type) {
        E[] constants = type.getEnumConstants();
        return TypeConverter.of(Integer.class, Enum::ordinal, ordinal -> {
            if (ordinal < 0 || ordinal >= constants.length) {
                throw new MappingException("Ordinal " + ordinal + " is out of range for enum " + type.getName());
            }
            return constants[ordinal];
        });
    }

    /**
     * Maps a primitive class to its wrapper.
     *
     * @param type any class
     * @return the wrapper for primitives, otherwise the argument
     */
    public static Class<?> box(Class<?> type) {
        return type.isPrimitive() ? WRAPPERS.get(type) : type;
    }
}
