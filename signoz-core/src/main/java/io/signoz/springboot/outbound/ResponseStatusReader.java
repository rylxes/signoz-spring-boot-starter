package io.signoz.springboot.outbound;

import java.lang.reflect.Method;

/**
 * Reads the HTTP status code of a client response across Spring Framework 5, 6 and 7.
 *
 * <p>signoz-core compiles against Spring 5, where a direct call to a status method binds to a
 * signature that later versions changed or removed:
 * <ul>
 *   <li>{@code getStatusCode()} / {@code statusCode()} return {@code HttpStatus} on Spring 5
 *       and {@code HttpStatusCode} on Spring 6 and 7;</li>
 *   <li>{@code ClientResponse.rawStatusCode()} was removed in Spring 6.1, and
 *       {@code ClientHttpResponse.getRawStatusCode()} in Spring 7.0.</li>
 * </ul>
 * The raw method is preferred when present, because on Spring 5 the typed method throws for
 * a non-standard code such as 299. Otherwise the typed method's {@code value()} is used, looked up
 * on the declared return type so that a non-public implementation class is never invoked
 * directly. Methods are resolved once per response type.
 */
final class ResponseStatusReader {

    /** Logged when the status cannot be read. */
    static final int UNKNOWN = -1;

    private final Method rawStatus;
    private final Method typedStatus;
    private final Method typedValue;

    /**
     * @param responseType     the response type, for example {@code ClientHttpResponse}
     * @param rawStatusName    the {@code int}-returning method, absent on newer Spring versions
     * @param typedStatusName  the method returning a status object with a {@code value()} method
     */
    ResponseStatusReader(Class<?> responseType, String rawStatusName, String typedStatusName) {
        this.rawStatus = findIntMethod(responseType, rawStatusName);
        Method typed = findMethod(responseType, typedStatusName);
        Method value = typed != null ? findIntMethod(typed.getReturnType(), "value") : null;
        this.typedStatus = value != null ? typed : null;
        this.typedValue = value;
    }

    /**
     * Returns the response's status code, or {@link #UNKNOWN}. Never throws, so logging the
     * status cannot fail the call being logged.
     */
    int read(Object response) {
        if (response == null) {
            return UNKNOWN;
        }
        if (rawStatus != null) {
            try {
                return (Integer) rawStatus.invoke(response);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Fall through to the typed status.
            }
        }
        if (typedStatus != null) {
            try {
                return (Integer) typedValue.invoke(typedStatus.invoke(response));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return UNKNOWN;
            }
        }
        return UNKNOWN;
    }

    private static Method findIntMethod(Class<?> type, String name) {
        Method method = findMethod(type, name);
        return method != null && method.getReturnType() == int.class ? method : null;
    }

    private static Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
