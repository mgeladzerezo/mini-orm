package io.github.mgeladzerezo.miniorm.error;

/**
 * Transaction demarcation was misused, or a transaction marked rollback-only was asked to commit.
 */
public class TransactionException extends OrmException {

    /**
     * @param message what went wrong
     */
    public TransactionException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
