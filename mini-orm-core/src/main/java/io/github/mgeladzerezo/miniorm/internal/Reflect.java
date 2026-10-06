package io.github.mgeladzerezo.miniorm.internal;

import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.error.OrmException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Reflection calls with the checked exceptions turned into the ORM's own. */
public final class Reflect {

    private Reflect() {
    }

    public static Object get(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new OrmException("Cannot read " + describe(field), e);
        }
    }

    public static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new OrmException("Cannot assign " + (value == null ? "null" : value.getClass().getName())
                    + " to " + describe(field), e);
        }
    }

    public static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new OrmException("Cannot call " + method, e);
        } catch (InvocationTargetException e) {
            throw unwrap(e, "Method " + method + " threw");
        }
    }

    public static <T> T instantiate(Constructor<T> constructor, Object... args) {
        try {
            return constructor.newInstance(args);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException e) {
            throw new OrmException("Cannot instantiate " + constructor.getDeclaringClass().getName(), e);
        } catch (InvocationTargetException e) {
            throw unwrap(e, "Constructor of " + constructor.getDeclaringClass().getName() + " threw");
        }
    }

    private static RuntimeException unwrap(InvocationTargetException e, String message) {
        return e.getCause() instanceof RuntimeException runtime ? runtime : new OrmException(message, e.getCause());
    }

    /** Makes a member accessible, explaining the JPMS fix if the package is not open. */
    public static <A extends java.lang.reflect.AccessibleObject> A open(A member, Class<?> owner) {
        try {
            member.setAccessible(true);
            return member;
        } catch (InaccessibleObjectException e) {
            throw new MappingException("Cannot access members of " + owner.getName() + ". If it lives in a named"
                    + " module, add: opens " + owner.getPackageName() + " to io.github.mgeladzerezo.miniorm.core;", e);
        }
    }

    public static <T> Constructor<T> noArgConstructor(Class<T> type) {
        try {
            return open(type.getDeclaredConstructor(), type);
        } catch (NoSuchMethodException e) {
            throw new MappingException(type.getName() + " needs a no-argument constructor (it may be"
                    + " package-private or protected)");
        }
    }

    /** Instance fields of the class and its superclasses, superclass fields first. */
    public static List<Field> instanceFields(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            hierarchy.addFirst(c);
        }
        List<Field> fields = new ArrayList<>();
        for (Class<?> c : hierarchy) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    public static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        } else if (type == char.class) {
            return '\0';
        } else if (type == long.class) {
            return 0L;
        } else if (type == float.class) {
            return 0f;
        } else if (type == double.class) {
            return 0d;
        } else if (type == byte.class) {
            return (byte) 0;
        } else if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }

    public static String describe(Field field) {
        return field.getDeclaringClass().getSimpleName() + "." + field.getName();
    }
}
