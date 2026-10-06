package io.github.mgeladzerezo.miniorm.error;

/**
 * The database schema does not match the entity metadata. The message lists every difference.
 */
public class SchemaValidationException extends OrmException {

    /**
     * @param message what went wrong
     */
    public SchemaValidationException(String message) {
        super(message);
    }
}
