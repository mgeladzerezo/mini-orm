package io.github.mgeladzerezo.miniorm.error;

/**
 * Root of every exception thrown by mini-orm. All of them are unchecked: a failed statement
 * is almost never something the immediate caller can repair, and checked exceptions would
 * make the lambda-based transaction and query APIs unusable.
 */
public class OrmException extends RuntimeException {

    /**
     * @param message what went wrong
     */
    public OrmException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public OrmException(String message, Throwable cause) {
        super(message, cause);
    }
}
