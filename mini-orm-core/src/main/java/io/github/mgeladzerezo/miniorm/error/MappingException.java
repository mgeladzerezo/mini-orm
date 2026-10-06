package io.github.mgeladzerezo.miniorm.error;

/**
 * An entity class, embeddable, converter or repository interface is declared in a way the ORM
 * cannot map. Raised when the session factory is built, not at first use.
 */
public class MappingException extends OrmException {

    /**
     * @param message what went wrong
     */
    public MappingException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public MappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
