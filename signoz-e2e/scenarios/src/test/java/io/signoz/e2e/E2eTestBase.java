package io.signoz.e2e;

import io.signoz.e2e.support.Http;
import io.signoz.e2e.support.OtlpReceiver;
import io.signoz.e2e.support.Ports;
import io.signoz.e2e.support.ServiceProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for the e2e scenarios: starts service A and service B as separate JVMs that point at each
 * other, with an in-process OTLP receiver on the collector's standard ports.
 */
public abstract class E2eTestBase {

    /** Planted wherever a secret goes; it must never reach a log line or an export. */
    protected static final String CANARY = "e2e-s3cret-canary";

    protected static final String SERVLET = "io.signoz.e2e.app.servlet.ServletApp";
    protected static final String REACTIVE = "io.signoz.e2e.app.reactive.ReactiveApp";

    protected static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    protected static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-0[01]");

    /** Every feature switch off: the isolation tests turn on just the one under test. */
    protected static final Map<String, String> ALL_FEATURES_OFF;

    static {
        Map<String, String> off = new LinkedHashMap<String, String>();
        off.put("signoz.logging.mode", "JSON");
        off.put("signoz.logging.mask-enabled", "false");
        off.put("signoz.tracing.enabled", "false");
        off.put("signoz.web.log-requests", "false");
        off.put("signoz.audit.enabled", "false");
        off.put("signoz.timed.enabled", "false");
        off.put("signoz.alerts.enabled", "false");
        off.put("signoz.outbound.enabled", "false");
        off.put("signoz.messaging.enabled", "false");
        off.put("signoz.sqs.enabled", "false");
        off.put("signoz.grpc.enabled", "false");
        off.put("signoz.websocket.enabled", "false");
        off.put("signoz.database.enabled", "false");
        off.put("signoz.user-context.enabled", "false");
        off.put("signoz.errors.enabled", "false");
        off.put("signoz.async.enabled", "false");
        off.put("signoz.logging.sampling.enabled", "false");
        ALL_FEATURES_OFF = Collections.unmodifiableMap(off);
    }

    private static OtlpReceiver receiver;

    protected final List<ServiceProcess> started = new ArrayList<ServiceProcess>();
    private Path logDir;

    /** The single receiver for the whole run: 4317/4318 can only be bound once. */
    protected static synchronized OtlpReceiver receiver() {
        if (receiver == null) {
            receiver = OtlpReceiver.start();
            Runtime.getRuntime().addShutdownHook(new Thread(receiver::close));
        }
        return receiver;
    }

    @BeforeEach
    void prepare(TestInfo info) {
        receiver().clear();
        logDir = Paths.get(System.getProperty("e2e.logs", "target/e2e-logs"),
                info.getTestClass().map(Class::getSimpleName).orElse("e2e"),
                info.getTestMethod().map(m -> m.getName()).orElse("test"));
    }

    @AfterEach
    void stopServices() {
        for (ServiceProcess p : started) {
            p.stopGracefully();
        }
        started.clear();
    }

    protected static int bootVersion() {
        return Integer.parseInt(System.getProperty("e2e.boot", "3"));
    }

    // --- starting services ---

    /** A two-service setup: A and B, each knowing the other's HTTP and gRPC ports. */
    protected final class Pair {
        public final ServiceProcess a;
        public final ServiceProcess b;

        Pair(ServiceProcess a, ServiceProcess b) {
            this.a = a;
            this.b = b;
        }
    }

    /** Options for one service of a pair. */
    protected static final class Options {
        String mainClass = SERVLET;
        final Map<String, String> properties = new LinkedHashMap<String, String>();
        final List<String> excludedJars = new ArrayList<String>();
        final List<String> jvmArgs = new ArrayList<String>();
        boolean log4j2;

        public static Options servlet() {
            return new Options();
        }

        public static Options reactive() {
            Options o = new Options();
            o.mainClass = REACTIVE;
            // A production WebFlux app runs on Netty; leave Tomcat off its classpath.
            o.excludedJars.add("tomcat-embed");
            o.excludedJars.add("spring-boot-starter-tomcat");
            return o;
        }

        public Options with(Map<String, String> values) {
            properties.putAll(values);
            return this;
        }

        public Options with(String key, String value) {
            properties.put(key, value);
            return this;
        }

        public Options withoutJars(String... prefixes) {
            excludedJars.addAll(Arrays.asList(prefixes));
            return this;
        }

        public Options jvmArg(String arg) {
            jvmArgs.add(arg);
            return this;
        }

        /** Runs on Log4j2 instead of Logback, as the logging guide's exclusion recipe produces. */
        public Options log4j2() {
            log4j2 = true;
            return this;
        }

        Options copy() {
            Options o = new Options();
            o.mainClass = mainClass;
            o.properties.putAll(properties);
            o.excludedJars.addAll(excludedJars);
            o.jvmArgs.addAll(jvmArgs);
            o.log4j2 = log4j2;
            return o;
        }
    }

    /** Starts A and B with the same options. */
    protected Pair startPair(Options options) {
        return startPair(options, options.copy());
    }

    protected Pair startPair(Options aOptions, Options bOptions) {
        int[] ports = Ports.free(4);
        int aPort = ports[0];
        int bPort = ports[1];
        int aGrpc = ports[2];
        int bGrpc = ports[3];
        ServiceProcess a = start("a", aOptions, aPort, aGrpc, bPort, bGrpc);
        ServiceProcess b = start("b", bOptions, bPort, bGrpc, aPort, aGrpc);
        a.awaitReady(90_000);
        b.awaitReady(90_000);
        return new Pair(a, b);
    }

    protected ServiceProcess startSingle(Options options) {
        int[] ports = Ports.free(2);
        ServiceProcess s = start("a", options, ports[0], ports[1], 1, 0);
        return s.awaitReady(90_000);
    }

    private ServiceProcess start(String name, Options o, int port, int grpcPort, int peerPort, int peerGrpcPort) {
        ServiceProcess.Builder builder = ServiceProcess.builder(name)
                .mainClass(o.mainClass)
                .classpath(classpath())
                .port(port)
                .property("e2e.name", name)
                .property("e2e.peer-url", "http://localhost:" + peerPort)
                .property("e2e.grpc.port", String.valueOf(grpcPort))
                .property("e2e.peer-grpc-port", String.valueOf(peerGrpcPort))
                .property("signoz.service-name", "e2e-" + name)
                .property("signoz.service-version", "9.9.9")
                .property("signoz.environment", "e2e")
                .property("signoz.tracing.export-schedule-delay-ms", "200")
                .properties(o.properties);
        for (String jar : o.excludedJars) {
            builder.excludeJars(jar);
        }
        if (o.log4j2) {
            builder.excludeJars("logback-classic", "logback-core", "log4j-to-slf4j", "spring-boot-starter-logging");
        } else {
            builder.excludeJars("log4j-core", "log4j-slf4j", "log4j-jul", "spring-boot-starter-log4j2");
        }
        for (String arg : o.jvmArgs) {
            builder.jvmArg(arg);
        }
        ServiceProcess process = builder.start(logDir);
        started.add(process);
        return process;
    }

    private static List<String> classpath() {
        List<String> cp = new ArrayList<String>();
        cp.add(System.getProperty("e2e.classes"));
        try {
            String file = new String(Files.readAllBytes(Paths.get(System.getProperty("e2e.classpath"))),
                    StandardCharsets.UTF_8).trim();
            cp.addAll(Arrays.asList(file.split(File.pathSeparator)));
        } catch (IOException e) {
            throw new IllegalStateException("Run the e2e tests through Maven (mvn verify -Pe2e)", e);
        }
        return cp;
    }

    protected static String agentJar() {
        return System.getProperty("e2e.agent");
    }

    // --- assertions shared by every scenario ---

    /**
     * The starter must not break the application: it still answers, nothing failed to load, and
     * no ERROR was logged except the ones the scenario provoked on purpose.
     */
    protected void assertHealthy(ServiceProcess service, String... expectedErrorMessages) throws IOException {
        assertThat(service.isAlive()).as(service.name() + " is running").isTrue();
        assertThat(Http.get(service.baseUrl() + "/e2e/ping").body).isEqualTo("pong");
        for (String line : service.lines()) {
            assertThat(line).as(service.name() + " output")
                    .doesNotContain("APPLICATION FAILED TO START")
                    .doesNotContain("NoSuchMethodError")
                    .doesNotContain("NoClassDefFoundError")
                    .doesNotContain("ClassNotFoundException")
                    .doesNotContain("LinkageError");
            if (line.contains("not eligible for getting processed by all BeanPostProcessors")) {
                assertThat(line).as(service.name() + ": a SigNoz bean was created too early")
                        .doesNotContain("io.signoz.springboot").doesNotContain("'signoz.");
            }
        }
        for (Map<String, Object> json : service.jsonLines()) {
            if ("ERROR".equals(json.get("level"))) {
                String message = String.valueOf(json.get("message"));
                boolean expected = false;
                for (String e : expectedErrorMessages) {
                    expected |= message.contains(e);
                }
                assertThat(expected).as(service.name() + " logged an unexpected ERROR: " + json).isTrue();
            }
        }
    }

    protected static Predicate<Map<String, Object>> messageContains(String text) {
        return json -> String.valueOf(json.get("message")).contains(text);
    }

    protected static String str(Map<String, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    protected static Map<String, String> headers(String... keyValues) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            h.put(keyValues[i], keyValues[i + 1]);
        }
        return h;
    }

    protected static String traceIdOf(String traceparent) {
        java.util.regex.Matcher m = TRACEPARENT.matcher(traceparent == null ? "" : traceparent);
        assertThat(m.matches()).as("valid traceparent: " + traceparent).isTrue();
        return m.group(1);
    }
}
