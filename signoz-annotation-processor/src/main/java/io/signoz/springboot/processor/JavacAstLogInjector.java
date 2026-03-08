package io.signoz.springboot.processor;

import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Concrete Javac AST-level log field injector.
 *
 * <p>This class directly references {@code com.sun.tools.javac} internal APIs.
 * It is intentionally NOT referenced by name from {@link JavacLogInjector} —
 * instead it is loaded reflectively so that if these internal types are absent
 * the APT bootstrap does not fail.
 *
 * <h3>What it does</h3>
 * For a class annotated with {@code @SigNozLog}, it prepends the following
 * field declaration to the class body at the AST level (before javac compiles
 * the class to bytecode):
 * <pre>
 *   private static final org.slf4j.Logger log =
 *       org.slf4j.LoggerFactory.getLogger(ThisClass.class);
 * </pre>
 *
 * <h3>Java 9+ module requirements</h3>
 * Consumer build tools must pass:
 * <pre>
 *   --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
 *   --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
 *   --add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
 *   --add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
 *   --add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED
 *   --add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
 *   --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
 *   --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
 * </pre>
 * The starter's POM adds these automatically when the starter dependency is used.
 */
public class JavacAstLogInjector extends JavacLogInjector {

    private static final String LOGGER_CLASS = "org.slf4j.Logger";
    private static final String LOGGER_FACTORY = "org.slf4j.LoggerFactory";
    private static final String FIELD_NAME = "log";

    private final Trees trees;
    private final TreeMaker treeMaker;
    private final Names names;
    private final Messager messager;

    public JavacAstLogInjector(ProcessingEnvironment processingEnv, Messager messager) {
        this.messager = messager;
        Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
        this.trees = JavacTrees.instance(processingEnv);
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    @Override
    public void injectLoggerField(TypeElement typeElement, String topic) {
        try {
            JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl) trees.getTree(typeElement);
            if (classDecl == null) {
                warn(typeElement, "Could not obtain class AST");
                return;
            }

            // Skip if a field named "log" already exists
            for (JCTree member : classDecl.defs) {
                if (member instanceof JCTree.JCVariableDecl) {
                    JCTree.JCVariableDecl var = (JCTree.JCVariableDecl) member;
                    if (FIELD_NAME.equals(var.name.toString())) {
                        return; // field already declared, respect it
                    }
                }
            }

            JCTree.JCVariableDecl logField = buildLoggerField(typeElement, topic);
            // Prepend to class body so `log` is the first member
            classDecl.defs = classDecl.defs.prepend(logField);

        } catch (Exception e) {
            warn(typeElement, "Failed to inject logger field: " + e.getMessage());
        }
    }

    /**
     * Builds: {@code private static final Logger log = LoggerFactory.getLogger(Foo.class);}
     */
    private JCTree.JCVariableDecl buildLoggerField(TypeElement typeElement, String topic) {
        // Logger type reference: org.slf4j.Logger
        JCTree.JCExpression loggerType = buildFqcnExpression(LOGGER_CLASS);

        // LoggerFactory.getLogger(...) call
        JCTree.JCExpression factoryRef = buildFqcnExpression(LOGGER_FACTORY);
        JCTree.JCFieldAccess getLogger = treeMaker.Select(
                factoryRef,
                names.fromString("getLogger"));

        // Argument: Foo.class  -OR-  "topic" (string literal if topic is set)
        JCTree.JCExpression arg;
        if (topic != null && !topic.isEmpty()) {
            arg = treeMaker.Literal(topic);
        } else {
            // ClassName.class
            String className = typeElement.getQualifiedName().toString();
            JCTree.JCExpression classRef = buildFqcnExpression(className);
            arg = treeMaker.Select(classRef, names.fromString("class"));
        }

        JCTree.JCMethodInvocation init = treeMaker.Apply(
                List.<JCTree.JCExpression>nil(),
                getLogger,
                List.of(arg));

        // Modifiers: private static final
        long flags = com.sun.tools.javac.code.Flags.PRIVATE
                | com.sun.tools.javac.code.Flags.STATIC
                | com.sun.tools.javac.code.Flags.FINAL;

        JCTree.JCModifiers modifiers = treeMaker.Modifiers(flags);

        return treeMaker.VarDef(
                modifiers,
                names.fromString(FIELD_NAME),
                loggerType,
                init);
    }

    /**
     * Converts a fully-qualified class name like {@code "org.slf4j.Logger"}
     * into a chained {@code JCFieldAccess} expression.
     */
    private JCTree.JCExpression buildFqcnExpression(String fqcn) {
        String[] parts = fqcn.split("\\.");
        JCTree.JCExpression expr = treeMaker.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            expr = treeMaker.Select(expr, names.fromString(parts[i]));
        }
        return expr;
    }

    private void warn(TypeElement element, String msg) {
        messager.printMessage(
                Diagnostic.Kind.WARNING,
                "[SigNoz] @SigNozLog on '" + element.getSimpleName() + "': " + msg);
    }
}
