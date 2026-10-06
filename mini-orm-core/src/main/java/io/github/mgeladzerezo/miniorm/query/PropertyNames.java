package io.github.mgeladzerezo.miniorm.query;

import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Turns a getter method reference into a property name.
 *
 * <p>Results are cached per lambda class. A method reference at a given call site is one
 * class for the life of the program, so the reflective {@code writeReplace} call happens once
 * per call site, not once per query. The cache holds classes weakly so it cannot pin an
 * application class loader.
 */
final class PropertyNames {

    private static final Map<Class<?>, String> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private PropertyNames() {
    }

    static String of(Property<?, ?> property) {
        if (property instanceof Attribute<?, ?> attribute) {
            return attribute.name();
        }
        String cached = CACHE.get(property.getClass());
        if (cached == null) {
            cached = resolve(property);
            CACHE.put(property.getClass(), cached);
        }
        return cached;
    }

    private static String resolve(Property<?, ?> property) {
        SerializedLambda lambda;
        try {
            Method writeReplace = property.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            lambda = (SerializedLambda) writeReplace.invoke(property);
        } catch (InaccessibleObjectException e) {
            throw new IllegalArgumentException("Cannot inspect the method reference. If the calling code lives in"
                    + " a named module, open its package to io.github.mgeladzerezo.miniorm.core.", e);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("A property must be given as a getter method reference such as"
                    + " User::getEmail, or as an Attribute", e);
        }
        String method = lambda.getImplMethodName();
        int kind = lambda.getImplMethodKind();
        boolean getterReference = (kind == MethodHandleInfo.REF_invokeVirtual
                || kind == MethodHandleInfo.REF_invokeInterface) && !method.startsWith("lambda$");
        if (!getterReference) {
            throw new IllegalArgumentException("A property must be a getter method reference such as"
                    + " User::getEmail; a lambda body cannot be mapped to a column (got " + method + ")");
        }
        return propertyName(method);
    }

    /** {@code getEmail -> email}, {@code isActive -> active}, {@code email -> email}, {@code getURL -> URL}. */
    static String propertyName(String method) {
        String stem;
        if (method.length() > 3 && method.startsWith("get") && Character.isUpperCase(method.charAt(3))) {
            stem = method.substring(3);
        } else if (method.length() > 2 && method.startsWith("is") && Character.isUpperCase(method.charAt(2))) {
            stem = method.substring(2);
        } else {
            return method;
        }
        // JavaBeans rule: a leading acronym keeps its case.
        if (stem.length() > 1 && Character.isUpperCase(stem.charAt(1))) {
            return stem;
        }
        return Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
    }
}
