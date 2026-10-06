package io.github.mgeladzerezo.miniorm.internal;

import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.proxy.EntityProxy;
import io.github.mgeladzerezo.miniorm.proxy.LazyInitializer;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates lazy-loading proxies for entity classes with the {@code java.lang.classfile} API.
 *
 * <p>For an entity {@code Author} the factory defines, once, a class equivalent to:
 *
 * <pre>{@code
 * public final class Author$MiniOrmProxy extends Author implements EntityProxy {
 *     private LazyInitializer $$initializer;
 *
 *     public Long getId()     { return (Long) $$initializer.id(); }          // no query
 *     public String getName() { return ((Author) $$initializer.target()).getName(); }
 *     // ... one forwarding override per overridable method ...
 * }
 * }</pre>
 *
 * <p>Every overridable method forwards to the real, managed instance, which the initializer
 * loads on first use. Forwarding (rather than copying the loaded state into the proxy) keeps a
 * single source of truth: dirty checking watches the real instance, and a change made through
 * the proxy is a change to that instance.
 *
 * <p>The class is defined with a private lookup in the entity's own package, so it has the
 * same access as hand-written code there (package-private and protected methods can be
 * overridden) and no {@code --add-opens} or agent is needed.
 */
public final class ProxyFactory {

    private static final String FIELD = "$$initializer";
    private static final ClassDesc INITIALIZER = desc(LazyInitializer.class);
    private static final MethodTypeDesc RETURNS_OBJECT = MethodTypeDesc.of(ConstantDescs.CD_Object);

    /**
     * Shared by every factory in the JVM: a class name can be defined only once per class loader,
     * so a second {@code MiniOrm} for the same entities (common in tests) must reuse the first
     * proxy class instead of generating it again.
     */
    private static final Map<Class<?>, Constructor<?>> CONSTRUCTORS = new ConcurrentHashMap<>();

    /**
     * Creates an uninitialized proxy for {@code metadata}'s entity.
     *
     * @throws MappingException if the entity class cannot be subclassed
     */
    public Object create(EntityMetadata<?> metadata, LazyInitializer initializer) {
        Constructor<?> constructor = CONSTRUCTORS.computeIfAbsent(metadata.entityClass(), c -> generate(metadata));
        EntityProxy proxy = (EntityProxy) Reflect.instantiate(constructor);
        proxy.$$miniOrmBind(initializer);
        return proxy;
    }

    /** Generates the proxy class now, so an unproxyable entity fails at startup. */
    public void prepare(EntityMetadata<?> metadata) {
        CONSTRUCTORS.computeIfAbsent(metadata.entityClass(), c -> generate(metadata));
    }

    private static Constructor<?> generate(EntityMetadata<?> metadata) {
        Class<?> entityClass = metadata.entityClass();
        String reason = null;
        Constructor<?> superConstructor = null;
        try {
            superConstructor = entityClass.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            reason = "it has no no-argument constructor";
        }
        if (Modifier.isFinal(entityClass.getModifiers())) {
            reason = "the class is final";
        } else if (superConstructor != null && Modifier.isPrivate(superConstructor.getModifiers())) {
            reason = "its no-argument constructor is private (make it package-private or protected)";
        }
        if (reason != null) {
            throw new MappingException(entityClass.getName() + " cannot be lazily loaded because " + reason
                    + ". Fix the class, or map references to it with @ManyToOne(fetch = FetchType.EAGER).");
        }
        try {
            byte[] bytes = buildClass(metadata);
            Class<?> proxyClass = MethodHandles.privateLookupIn(entityClass, MethodHandles.lookup()).defineClass(bytes);
            return Reflect.open(proxyClass.getDeclaredConstructor(), entityClass);
        } catch (IllegalAccessException | NoSuchMethodException | LinkageError e) {
            throw new MappingException("Could not define a lazy proxy for " + entityClass.getName()
                    + ". If it lives in a named module, add: opens " + entityClass.getPackageName()
                    + " to io.github.mgeladzerezo.miniorm.core;", e);
        }
    }

    private static byte[] buildClass(EntityMetadata<?> metadata) {
        Class<?> entityClass = metadata.entityClass();
        ClassDesc superDesc = desc(entityClass);
        ClassDesc proxyDesc = ClassDesc.ofDescriptor("L" + entityClass.getName().replace('.', '/') + "$MiniOrmProxy;");
        String idProperty = metadata.idColumn().property();
        Set<String> idGetters = Set.of(idProperty,
                "get" + idProperty.substring(0, 1).toUpperCase(Locale.ROOT) + idProperty.substring(1));

        return ClassFile.of().build(proxyDesc, classBuilder -> {
            classBuilder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC);
            classBuilder.withSuperclass(superDesc);
            classBuilder.withInterfaceSymbols(desc(EntityProxy.class));
            classBuilder.withField(FIELD, INITIALIZER, ClassFile.ACC_PRIVATE);

            classBuilder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                    code -> code.aload(0)
                            .invokespecial(superDesc, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                            .return_());
            classBuilder.withMethodBody("$$miniOrmInitializer", MethodTypeDesc.of(INITIALIZER), ClassFile.ACC_PUBLIC,
                    code -> code.aload(0).getfield(proxyDesc, FIELD, INITIALIZER).areturn());
            classBuilder.withMethodBody("$$miniOrmBind", MethodTypeDesc.of(ConstantDescs.CD_void, INITIALIZER),
                    ClassFile.ACC_PUBLIC,
                    code -> code.aload(0).aload(1).putfield(proxyDesc, FIELD, INITIALIZER).return_());

            for (Method method : overridableMethods(entityClass)) {
                MethodTypeDesc type = MethodTypeDesc.of(desc(method.getReturnType()),
                        Arrays.stream(method.getParameterTypes()).map(ProxyFactory::desc).toList());
                int flags = method.getModifiers() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED);
                boolean idGetter = method.getParameterCount() == 0 && idGetters.contains(method.getName())
                        && method.getReturnType() == metadata.idColumn().javaType()
                        && !method.getReturnType().isPrimitive();
                classBuilder.withMethodBody(method.getName(), type, flags, code -> {
                    code.aload(0).getfield(proxyDesc, FIELD, INITIALIZER);
                    if (idGetter) {
                        // The id is known without loading: answer from the initializer.
                        code.invokevirtual(INITIALIZER, "id", RETURNS_OBJECT).checkcast(desc(method.getReturnType())).areturn();
                    } else {
                        forwardToTarget(code, superDesc, method, type);
                    }
                });
            }
        });
    }

    /** Emits {@code return ((Entity) initializer.target()).method(args...)}. */
    private static void forwardToTarget(CodeBuilder code, ClassDesc entity, Method method, MethodTypeDesc type) {
        code.invokevirtual(INITIALIZER, "target", RETURNS_OBJECT).checkcast(entity);
        int slot = 1;
        for (Class<?> parameter : method.getParameterTypes()) {
            TypeKind kind = TypeKind.from(parameter);
            code.loadLocal(kind, slot);
            slot += kind.slotSize();
        }
        code.invokevirtual(entity, method.getName(), type);
        code.return_(TypeKind.from(method.getReturnType()));
    }

    /**
     * Instance methods of the entity hierarchy that a subclass in the same package may
     * override: not private, static or final, and (for package-private ones) declared in the
     * entity's own package. The most derived declaration of each signature wins.
     */
    private static List<Method> overridableMethods(Class<?> entityClass) {
        List<Method> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                String signature = method.getName() + MethodType
                        .methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
                if (!seen.add(signature)) {
                    continue;
                }
                boolean packagePrivate = !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers);
                if (Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)
                        || packagePrivate && !c.getPackageName().equals(entityClass.getPackageName())) {
                    continue;
                }
                methods.add(method);
            }
        }
        return methods;
    }

    private static ClassDesc desc(Class<?> type) {
        return ClassDesc.ofDescriptor(type.descriptorString());
    }
}
