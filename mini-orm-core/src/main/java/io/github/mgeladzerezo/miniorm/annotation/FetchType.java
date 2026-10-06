package io.github.mgeladzerezo.miniorm.annotation;

/** When an association is loaded. */
public enum FetchType {
    /** On first access. */
    LAZY,
    /** Together with the owning entity. */
    EAGER
}
