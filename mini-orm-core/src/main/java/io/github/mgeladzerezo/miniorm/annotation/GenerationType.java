package io.github.mgeladzerezo.miniorm.annotation;

/** Primary key generation strategies. */
public enum GenerationType {
    /** The database assigns the key (identity column); it is read back after the INSERT, at flush. */
    IDENTITY,
    /** The key comes from a database sequence, fetched in blocks, and is known right after {@code persist}. */
    SEQUENCE,
    /** A random {@code java.util.UUID} generated in memory at {@code persist}. */
    UUID
}
