package io.github.mgeladzerezo.miniorm.error;

/**
 * A row that was expected to exist does not: a lazy reference pointed at a missing row, or
 * {@code refresh} was called for a deleted entity.
 */
public class EntityNotFoundException extends OrmException {

    /**
     * @param message what went wrong
     */
    public EntityNotFoundException(String message) {
        super(message);
    }
}
