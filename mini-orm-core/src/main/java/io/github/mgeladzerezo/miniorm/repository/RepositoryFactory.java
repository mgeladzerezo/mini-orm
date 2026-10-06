package io.github.mgeladzerezo.miniorm.repository;

import io.github.mgeladzerezo.miniorm.Session;
import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.mapping.MetadataRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds repository implementations with a JDK dynamic proxy. Methods inherited from
 * {@link Repository} go to a plain implementation; every other abstract method is parsed by
 * {@code DerivedQuery} when the proxy is created, so a method name that cannot be understood
 * fails at that point with a message that lists the properties it could have meant. Default
 * methods of the interface run as written.
 *
 * <p>A dynamic proxy was chosen over bytecode generation because the repository is an
 * interface, which is exactly what {@code java.lang.reflect.Proxy} is for.
 */
public final class RepositoryFactory {

    private RepositoryFactory() {
    }

    /**
     * Creates a repository bound to {@code session}.
     *
     * @param session        the session whose transaction and cache the repository uses
     * @param registry       entity metadata
     * @param repositoryType an interface that extends {@link Repository} with concrete type arguments
     * @param <R>            the repository type
     * @return the implementation
     * @throws MappingException if the interface is malformed or a derived finder cannot be parsed
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <R extends Repository<?, ?>> R create(Session session, MetadataRegistry registry,
                                                       Class<R> repositoryType) {
        if (!repositoryType.isInterface()) {
            throw new MappingException(repositoryType.getName() + " must be an interface");
        }
        Class<?> entityClass = entityTypeOf(repositoryType);
        EntityMetadata<?> metadata = registry.get(entityClass);
        Repository<?, ?> base = new SimpleRepository(session, entityClass, metadata);
        Map<Method, DerivedQuery> derived = new HashMap<>();
        for (Method method : repositoryType.getMethods()) {
            if (method.getDeclaringClass() != Repository.class && !method.isDefault()
                    && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                derived.put(method, DerivedQuery.parse(method, metadata));
            }
        }
        InvocationHandler handler = (proxy, method, arguments) -> {
            try {
                if (method.getDeclaringClass() == Repository.class) {
                    return method.invoke(base, arguments);
                }
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> repositoryType.getSimpleName() + " for " + entityClass.getSimpleName();
                    };
                }
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, arguments);
                }
                return derived.get(method).execute(session, arguments == null ? new Object[0] : arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (R) Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class<?>[] {repositoryType}, handler);
    }

    /** Finds the {@code T} of {@code Repository<T, ID>} by walking the interface hierarchy. */
    private static Class<?> entityTypeOf(Class<?> type) {
        for (Type candidate : type.getGenericInterfaces()) {
            if (candidate instanceof ParameterizedType parameterized && parameterized.getRawType() == Repository.class) {
                if (parameterized.getActualTypeArguments()[0] instanceof Class<?> entity) {
                    return entity;
                }
                break;
            }
            if (candidate instanceof Class<?> parent && Repository.class.isAssignableFrom(parent)) {
                try {
                    return entityTypeOf(parent);
                } catch (MappingException ignored) {
                    // keep looking in the other super-interfaces
                }
            }
        }
        throw new MappingException(type.getName() + " must extend Repository<Entity, Id> with concrete type arguments");
    }
}
