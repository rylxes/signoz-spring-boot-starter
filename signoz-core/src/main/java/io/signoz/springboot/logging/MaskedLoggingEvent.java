package io.signoz.springboot.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.signoz.springboot.masking.MaskingRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps a Logback {@link ILoggingEvent} so that the formatted message and MDC an encoder
 * reads are already masked.
 *
 * <p>Masking has to happen before serialisation: once a JSON payload in the message is
 * embedded in a JSON log line its quotes are escaped ({@code \"password\":\"…\"}), and the
 * {@code "field":"value"} matching in {@link MaskingRegistry#maskJsonString(String)} no longer
 * sees it. The message is masked with {@code maskJsonString}; MDC values on sensitive keys with
 * their field strategy and all others with the message-level patterns.
 *
 * <p>A dynamic proxy rather than a class implementing {@code ILoggingEvent}: the interface
 * gained methods in Logback 1.3 ({@code getInstant}, {@code getKeyValuePairs}, …), and a class
 * compiled against Logback 1.2 would fail with {@code AbstractMethodError} on newer versions.
 * Every method other than the masked ones is delegated unchanged.
 */
final class MaskedLoggingEvent implements InvocationHandler {

    private final ILoggingEvent delegate;
    private final MaskingRegistry registry;
    private String maskedMessage;
    private Map<String, String> maskedMdc;

    private MaskedLoggingEvent(ILoggingEvent delegate, MaskingRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /**
     * Returns a view of {@code event} with masked message and MDC. Masking is eager, so a
     * failure surfaces here, before anything is serialised.
     */
    static ILoggingEvent wrap(ILoggingEvent event, MaskingRegistry registry) {
        MaskedLoggingEvent handler = new MaskedLoggingEvent(event, registry);
        handler.maskedMessage = registry.maskJsonString(event.getFormattedMessage());
        handler.maskedMdc = maskMdc(event.getMDCPropertyMap(), registry);
        return (ILoggingEvent) Proxy.newProxyInstance(
                ILoggingEvent.class.getClassLoader(), new Class<?>[]{ILoggingEvent.class}, handler);
    }

    static Map<String, String> maskMdc(Map<String, String> mdc, MaskingRegistry registry) {
        if (mdc == null || mdc.isEmpty()) {
            return mdc;
        }
        Map<String, String> masked = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : mdc.entrySet()) {
            String value = e.getValue();
            masked.put(e.getKey(), value == null ? null
                    : registry.isSensitiveField(e.getKey())
                    ? registry.mask(e.getKey(), value)
                    : registry.maskMessage(value));
        }
        return Collections.unmodifiableMap(masked);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int argc = args == null ? 0 : args.length;
        if (argc == 0) {
            if ("getFormattedMessage".equals(name)) {
                return maskedMessage;
            }
            if ("getMDCPropertyMap".equals(name) || "getMdc".equals(name)) {
                return maskedMdc;
            }
            if ("toString".equals(name)) {
                return delegate.toString();
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
        } else if (argc == 1 && "equals".equals(name)) {
            return proxy == args[0];
        }
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
