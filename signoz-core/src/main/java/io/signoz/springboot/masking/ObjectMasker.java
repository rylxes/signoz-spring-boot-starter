package io.signoz.springboot.masking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces a masked copy of a DTO so the object itself can be handed to a logger.
 *
 * <p>{@link MaskingRegistry} works on strings that have already been rendered. That is a backstop:
 * it depends on the serialised form still carrying recognisable {@code "field":"value"} pairs, and
 * it silently misses anything positional (a CSV row) or renamed. Masking the object first removes
 * that dependency — the sensitive value is gone before anything formats it, whatever the sink.
 *
 * <pre>
 * log.info("processing {}", objectMasker.mask(purchaseRequest));
 * </pre>
 *
 * <p>Field names are matched against {@link MaskingRegistry}, so {@code signoz.logging.profiles},
 * {@code masked-fields} and {@code field-strategies} all apply unchanged.
 *
 * <p><strong>Fails closed.</strong> If the copy cannot be made, {@code null} is returned and an
 * error logged. Returning the original would hand unmasked data to the logger, which is the exact
 * failure this class exists to prevent.
 */
public class ObjectMasker {

    private static final Logger log = LoggerFactory.getLogger(ObjectMasker.class);

    private final MaskingRegistry registry;

    public ObjectMasker(MaskingRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return a masked copy, the original if there is nothing to mask, or {@code null} if masking
     *         failed
     */
    @SuppressWarnings("unchecked")
    public <T> T mask(T source) {
        if (source == null) {
            return null;
        }
        Class<?> type = source.getClass();
        if (type.equals(String.class)) {
            return (T) registry.maskJsonString((String) source);
        }
        if (isOpaque(type)) {
            return source;
        }

        try {
            Object copy = type.getDeclaredConstructor().newInstance();
            for (Field field : type.getDeclaredFields()) {
                // Static fields are not per-instance state, and setting a `static final` (every
                // Serializable DTO has serialVersionUID) throws IllegalAccessException. Letting that
                // reach the catch below would abandon the whole copy.
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(source);
                if (value == null) {
                    continue;
                }
                field.set(copy, maskValue(field.getName(), field.getType(), value));
            }
            return (T) copy;
        } catch (Exception e) {
            log.error("[SigNoz] Object masking failed for {}; withholding it from the log line",
                    type.getName(), e);
            return null;
        }
    }

    private Object maskValue(String fieldName, Class<?> declaredType, Object value) {
        if (declaredType.equals(String.class)) {
            return registry.mask(fieldName, (String) value);
        }
        if (value instanceof Map) {
            return maskMapValues((Map<?, ?>) value);
        }
        if (isOpaque(value.getClass())) {
            // Numbers, enums, dates, collections: carried through so the log line keeps its
            // diagnostic fields (transaction id, rrn, timestamps).
            return value;
        }
        return mask(value);
    }

    /** Masks map values by their key, so {@code metadata.put("cardPan", ...)} is covered too. */
    private Map<Object, Object> maskMapValues(Map<?, ?> source) {
        Map<Object, Object> masked = new LinkedHashMap<Object, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key instanceof String && value instanceof String) {
                masked.put(key, registry.mask((String) key, (String) value));
            } else {
                masked.put(key, value);
            }
        }
        return masked;
    }

    /**
     * Types that are copied by reference rather than recursed into: no useful field structure to
     * mask, and no accessible no-arg constructor to rebuild them with.
     */
    private static boolean isOpaque(Class<?> type) {
        if (type.isPrimitive() || type.isEnum() || type.isArray()) {
            return true;
        }
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jakarta.") || name.startsWith("sun.")
                || name.startsWith("com.sun.");
    }
}
