package io.signoz.springboot.processor;

import io.signoz.springboot.annotation.SigNozLog;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * Annotation processor that injects a private static final SLF4J {@code Logger}
 * field into every class annotated with {@link SigNozLog}.
 *
 * <p><strong>On Java 8</strong>: Uses {@code com.sun.tools.javac} internal APIs
 * (available via {@code tools.jar}) to modify the AST directly, injecting:
 * <pre>
 *   private static final org.slf4j.Logger log =
 *       org.slf4j.LoggerFactory.getLogger(ThisClass.class);
 * </pre>
 *
 * <p><strong>On Java 9–21</strong>: The same internal APIs are accessible when
 * the consumer's build configures {@code --add-opens} for {@code jdk.compiler}.
 * The starter's POM configures these automatically for consuming projects.
 *
 * <p><strong>Graceful fallback</strong>: If the internal APIs are inaccessible
 * (GraalVM native-image, restricted security policy, etc.), the processor emits
 * a {@code WARNING} and skips the injection. The developer can then declare
 * {@code private static final Logger log = LoggerFactory.getLogger(MyClass.class);}
 * manually — all SigNoz enrichment still applies via Logback configuration.
 */
@SupportedAnnotationTypes("io.signoz.springboot.annotation.SigNozLog")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class SigNozLogProcessor extends AbstractProcessor {

    private Messager messager;
    private Filer filer;
    private JavacLogInjector injector;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
        this.injector = JavacLogInjector.tryCreate(processingEnv, messager);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || annotations.isEmpty()) {
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(SigNozLog.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@SigNozLog can only be applied to classes",
                        element);
                continue;
            }

            TypeElement typeElement = (TypeElement) element;
            SigNozLog annotation = typeElement.getAnnotation(SigNozLog.class);

            if (injector != null) {
                injector.injectLoggerField(typeElement, annotation.topic());
            } else {
                messager.printMessage(
                        Diagnostic.Kind.WARNING,
                        "@SigNozLog: could not inject logger field into '"
                                + typeElement.getSimpleName()
                                + "' — compiler internals unavailable. "
                                + "Please declare: "
                                + "private static final org.slf4j.Logger log = "
                                + "org.slf4j.LoggerFactory.getLogger("
                                + typeElement.getSimpleName()
                                + ".class);",
                        element);
            }
        }

        return true;
    }
}
