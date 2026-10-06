package io.github.mgeladzerezo.miniorm.error;

/**
 * {@code single()} or a derived finder returning one object matched more than one row.
 */
public class NonUniqueResultException extends OrmException {

    /**
     * @param message what went wrong
     */
    public NonUniqueResultException(String message) {
        super(message);
    }
}
