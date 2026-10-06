package io.github.mgeladzerezo.miniorm.error;

/**
 * A lazy proxy or collection was touched after its session was closed, or after the owning
 * entity was detached. The message names the entity and the association.
 */
public class LazyInitializationException extends OrmException {

    /**
     * @param message what went wrong
     */
    public LazyInitializationException(String message) {
        super(message);
    }
}
