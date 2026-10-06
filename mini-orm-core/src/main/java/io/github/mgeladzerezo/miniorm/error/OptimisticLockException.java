package io.github.mgeladzerezo.miniorm.error;

/**
 * An UPDATE or DELETE guarded by a {@code @Version} column matched no row: another transaction
 * changed or deleted the entity after this session read it. The transaction should be rolled
 * back and the work retried on fresh state.
 */
public class OptimisticLockException extends OrmException {

    private final transient Class<?> entityClass;
    private final transient Object id;
    private final transient Object expectedVersion;

    /**
     * @param entityClass     type of the stale entity
     * @param id              its primary key
     * @param expectedVersion the version this session had read
     */
    public OptimisticLockException(Class<?> entityClass, Object id, Object expectedVersion) {
        super(entityClass.getSimpleName() + " with id " + id + " was modified or deleted by another transaction"
                + " (expected version " + expectedVersion + ")");
        this.entityClass = entityClass;
        this.id = id;
        this.expectedVersion = expectedVersion;
    }

    /**
     * @return type of the stale entity
     */
    public Class<?> entityClass() {
        return entityClass;
    }

    /**
     * @return primary key of the stale entity
     */
    public Object id() {
        return id;
    }

    /**
     * @return the version this session expected to find in the row
     */
    public Object expectedVersion() {
        return expectedVersion;
    }
}
