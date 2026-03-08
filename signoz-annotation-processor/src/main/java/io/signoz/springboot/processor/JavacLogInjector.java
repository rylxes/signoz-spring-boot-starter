package io.signoz.springboot.processor;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Encapsulates the Javac internal API usage for injecting a logger field.
 *
 * <p>Loaded reflectively so that if the compiler internals are unavailable
 * (e.g. GraalVM, restricted JDK) the entire processor does not fail to load —
 * it simply returns {@code null} from {@link #tryCreate} and the outer processor
 * emits a friendly warning instead.
 *
 * <p>The actual AST manipulation is in {@link JavacAstLogInjector}, which imports
 * the internal {@code com.sun.tools.javac.*} types. This class acts as a safe
 * dispatcher using reflection.
 */
public abstract class JavacLogInjector {

    /**
     * Attempts to create a working injector backed by Javac internals.
     * Returns {@code null} if the internals are inaccessible.
     */
    public static JavacLogInjector tryCreate(ProcessingEnvironment env, Messager messager) {
        try {
            // Load the concrete impl class via reflection so that if it fails to load
            // (missing internal APIs) we catch it here rather than crashing the processor.
            Class<?> implClass = Class.forName(
                    "io.signoz.springboot.processor.JavacAstLogInjector");
            return (JavacLogInjector) implClass
                    .getConstructor(ProcessingEnvironment.class, Messager.class)
                    .newInstance(env, messager);
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[SigNoz] @SigNozLog APT: Javac internals not accessible ("
                            + e.getClass().getSimpleName() + "). "
                            + "Logger field injection will be skipped.");
            return null;
        }
    }

    /**
     * Injects {@code private static final Logger log = LoggerFactory.getLogger(clazz)}
     * into the given class element's AST.
     *
     * @param typeElement the class to inject into
     * @param topic       optional logger topic; if blank, the class canonical name is used
     */
    public abstract void injectLoggerField(TypeElement typeElement, String topic);
}
