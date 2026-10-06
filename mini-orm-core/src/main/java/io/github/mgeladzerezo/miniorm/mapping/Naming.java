package io.github.mgeladzerezo.miniorm.mapping;

import java.util.Locale;

/** Default names: Java camelCase to SQL snake_case. */
public final class Naming {

    private Naming() {
    }

    /**
     * Converts a Java identifier to snake_case: {@code createdAt} becomes {@code created_at},
     * {@code OrderItem} becomes {@code order_item}, {@code HTTPStatus} becomes {@code http_status}.
     *
     * @param identifier a class or field name
     * @return the snake_case form in lower case
     */
    public static String snakeCase(String identifier) {
        StringBuilder out = new StringBuilder(identifier.length() + 4);
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                char previous = identifier.charAt(i - 1);
                boolean nextIsLower = i + 1 < identifier.length() && Character.isLowerCase(identifier.charAt(i + 1));
                // A new word starts after a lower-case letter or digit, or at the last capital of an acronym.
                if (!Character.isUpperCase(previous) && previous != '_' || Character.isUpperCase(previous) && nextIsLower) {
                    out.append('_');
                }
            }
            out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
