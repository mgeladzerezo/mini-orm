package io.github.mgeladzerezo.miniorm.error;

/**
 * At flush an entity referenced, through a {@code @ManyToOne}, an object that was never
 * persisted. There is no cascading: persist the referenced entity first.
 */
public class TransientReferenceException extends OrmException {

    /**
     * @param message what went wrong
     */
    public TransientReferenceException(String message) {
        super(message);
    }
}
