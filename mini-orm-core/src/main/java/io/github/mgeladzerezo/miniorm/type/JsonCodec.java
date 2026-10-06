package io.github.mgeladzerezo.miniorm.type;

import java.lang.reflect.Type;

/**
 * The hook behind {@code @Json} fields. mini-orm has no JSON library of its own; plug in
 * Jackson, Gson or anything else with two lambdas.
 */
public interface JsonCodec {

    /**
     * Serializes a field value.
     *
     * @param value a non-null value
     * @return its JSON text
     */
    String write(Object value);

    /**
     * Deserializes a column value.
     *
     * @param json JSON text as stored
     * @param type the generic type of the field, for example {@code List<String>}
     * @return the field value
     */
    Object read(String json, Type type);
}
