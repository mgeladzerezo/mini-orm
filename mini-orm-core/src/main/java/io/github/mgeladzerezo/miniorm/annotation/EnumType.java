package io.github.mgeladzerezo.miniorm.annotation;

/** How an enum is stored. */
public enum EnumType {
    /** By {@code name()}; survives reordering of constants. */
    STRING,
    /** By {@code ordinal()}; compact but breaks if constants are reordered. */
    ORDINAL
}
