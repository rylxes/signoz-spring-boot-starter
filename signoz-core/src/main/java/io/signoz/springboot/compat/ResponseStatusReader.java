package io.signoz.springboot.compat;

import java.lang.reflect.Method;

/**
 * Reads the HTTP status code of a client or server response across Spring Framework 5, 6 and 7.
 * Internal to the starter; not part of its public API.
 *
 * <p>signoz-core compiles against Spring 5, where a direct call to a status method binds to a
 * signature that later versions changed or removed:
 * <ul>
 *   <li>{@code getStatusCode()} / {@code statusCode()} return {@code HttpStatus} on Spring 5
 *       and {@code HttpStatusCode} on Spring 6 and 7;</li>
 *   <li>{@code ClientResponse.rawStatusCode()} was removed in Spring 6.1, and
 *       {@code ClientHttpResponse.getRawStatusCode()} and
 *       {@code ServerHttpResponse.getRawStatusCode()} in Spring 7.0.</li>
 * </ul>
 * The raw method ({@code int}, or {@code Integer} where the status may be unset) is preferred
 * when present, because on Spring 5 the typed method throws for, or cannot represent, a
 * non-standard code such as 499. Otherwise the typed method's {@code value()} is used, looked up
 * on the declared return type so that a non-public implementation class is never invoked
 * directly. Methods are resolved once per reader.
 */
public final class ResponseStatusReader {

    /** Returned by {@link #read} when the status cannot be read. */
    public static final int UNKNOWN = -1;

    private final Method rawStatus;
    private final Method typedStatus;
    private final Method typedValue;

    /**
     * @param responseType     the response type, for example {@code ClientHttpResponse}
     * @param rawStatusName    the method returning the status as {@code int} or {@code Integer},
     *                         absent on newer Spring versions
     * @param typedStatusName  the method returning a status object with a {@code value()} method
     */
    public ResponseStatusReader(Class<?> responseType, String rawStatusName, String typedStatusName) {
        this.rawStatus = findStatusCodeMethod(responseType, rawStatusName);
        Method typed = findMethod(responseType, typedStatusName);
        Method value = typed != null ? findStatusCodeMethod(typed.getReturnType(), "value") : null;
        this.typedStatus = value != null ? typed : null;
        this.typedValue = value;
    }

    /**
     * Returns the response's status code, or {@link #UNKNOWN} when it has none or it cannot be
     * read. Never throws, so logging the status cannot fail the call being logged.
     */
    public int read(Object response) {
        Integer status = find(response);
        return status != null ? status : UNKNOWN;
    }

    /**
     * Returns the response's status code, or {@code null} when the response has no status yet
     * (a server response before one is set) or it cannot be read. Never throws.
     */
    public Integer find(Object response) {
        if (response == null) {
            return null;
        }
        if (rawStatus != null) {
            try {
                // null here means "not set", which the typed method would report the same way.
                return (Integer) rawStatus.invoke(response);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Fall through to the typed status.
            }
        }
        if (typedStatus != null) {
            try {
                Object status = typedStatus.invoke(response);
                return status != null ? (Integer) typedValue.invoke(status) : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Method findStatusCodeMethod(Class<?> type, String name) {
        Method method = findMethod(type, name);
        if (method == null) {
            return null;
        }
        Class<?> returnType = method.getReturnType();
        return returnType == int.class || returnType == Integer.class ? method : null;
    }

    private static Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
