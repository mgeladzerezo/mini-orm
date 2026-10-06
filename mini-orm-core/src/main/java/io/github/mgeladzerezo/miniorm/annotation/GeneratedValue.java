package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Asks the ORM to produce the primary key. Without this annotation the application assigns the
 * id before {@code persist}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface GeneratedValue {
    /** How the key is produced. */
    GenerationType strategy() default GenerationType.IDENTITY;

    /** Sequence name for {@link GenerationType#SEQUENCE}; empty means {@code <table>_seq}. */
    String sequence() default "";

    /**
     * How many ids one round trip to the sequence reserves. The sequence is created with this
     * increment, and the ids in between are handed out in memory.
     */
    int allocationSize() default 50;
}
