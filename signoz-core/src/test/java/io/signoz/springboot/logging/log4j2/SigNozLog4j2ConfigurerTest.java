package io.signoz.springboot.logging.log4j2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozProperties;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link SigNozLog4j2Configurer} against a real, standalone Log4j2 context configured
 * like Spring Boot's default {@code log4j2.xml} (a root {@code Console} pattern appender),
 * and logs through Log4j2's own Logger API.
 */
class SigNozLog4j2ConfigurerTest {

    /** Built at runtime so no credential-shaped literal sits in the source (secret scanners). */
    private static final String SENSITIVE = "sensitive-" + "payload-value";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private LoggerContext context;
    private SigNozProperties props;
    private final MaskingRegistry registry = new MaskingRegistry(new SigNozLoggingProperties());

    @BeforeEach
    void setUp() {
        // ConsoleAppender binds System.out when it is built, so swap it in first.
        originalOut = System.out;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8.name()));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }

        ConfigurationBuilder<BuiltConfiguration> b = ConfigurationBuilderFactory.newConfigurationBuilder();
        b.setStatusLevel(Level.ERROR);
        b.add(b.newAppender("Console", "CONSOLE")
                .addAttribute("target", "SYSTEM_OUT")
                .add(b.newLayout("PatternLayout").addAttribute("pattern", "PLAIN %m%n")));
        b.add(b.newRootLogger(Level.INFO).add(b.newAppenderRef("Console")));
        context = new LoggerContext("signoz-test-" + UUID.randomUUID());
        context.start(b.build());

        props = new SigNozProperties();
        props.getLogging().setMode(SigNozLoggingProperties.LoggingMode.JSON);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.clearAll();
        context.stop();
        System.setOut(originalOut);
    }

    @Test
    void replacesTheDefaultConsoleWithMaskedJsonCarryingTheThreadContext() throws Exception {
        SigNozLog4j2Configurer.configure(context, props, registry);

        LoggerConfig root = context.getConfiguration().getRootLogger();
        assertThat(root.getAppenders()).containsKey("SIGNOZ_JSON").doesNotContainKey("Console");

        ThreadContext.put("trace_id", "0af7651916cd43dd8448eb211c80319c");
        context.getLogger("com.example.Orders").info("login {}", "{\"password\":\"" + SENSITIVE + "\"}");

        List<JsonNode> lines = lines();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("message").asText()).doesNotContain(SENSITIVE);
        assertThat(lines.get(0).get("trace_id").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(lines.get(0).get("logger_name").asText()).isEqualTo("com.example.Orders");
        assertThat(stdout.toString("UTF-8")).doesNotContain("PLAIN");
    }

    @Test
    void isIdempotent() {
        SigNozLog4j2Configurer.configure(context, props, registry);
        SigNozLog4j2Configurer.configure(context, props, registry);

        assertThat(context.getConfiguration().getRootLogger().getAppenders()).hasSize(1);
    }

    @Test
    void leavesAnExistingJsonAppenderAlone() {
        LoggerConfig root = context.getConfiguration().getRootLogger();
        org.apache.logging.log4j.core.appender.ConsoleAppender userJson =
                org.apache.logging.log4j.core.appender.ConsoleAppender.newBuilder()
                        .setName("UserJson")
                        .setLayout(new SigNozLog4j2JsonLayout(null, 0))
                        .build();
        SigNozLog4j2Configurer.attach(context.getConfiguration(), root, userJson);

        SigNozLog4j2Configurer.configure(context, props, registry);

        assertThat(root.getAppenders()).containsKeys("Console", "UserJson").doesNotContainKey("SIGNOZ_JSON");
    }

    @Test
    void attachesTheOtlpAppenderInOtlpMode() {
        props.getLogging().setMode(SigNozLoggingProperties.LoggingMode.OTLP);

        SigNozLog4j2Configurer.configure(context, props, registry);

        assertThat(context.getConfiguration().getRootLogger().getAppenders())
                .containsKey("SIGNOZ_OTLP").containsKey("Console").doesNotContainKey("SIGNOZ_JSON");
        assertThat(context.getConfiguration().getRootLogger().getAppenders().get("SIGNOZ_OTLP").isStarted())
                .isTrue();
    }

    @Test
    void samplingDropsInfoButKeepsErrorsAcrossLoggingCallShapes() throws Exception {
        props.getLogging().getSampling().setEnabled(true);
        props.getLogging().getSampling().setRate(0.0);
        props.getLogging().getSampling().setAlwaysLogLevels(Arrays.asList("ERROR", "WARN"));

        SigNozLog4j2Configurer.configure(context, props, registry);
        SigNozLog4j2Configurer.configure(context, props, registry);

        org.apache.logging.log4j.Logger logger = context.getLogger("com.example.Sampled");
        logger.info("plain");
        logger.info("one {}", 1);
        logger.info("two {} {}", 1, 2);
        logger.info("many {} {} {} {} {} {} {} {} {} {} {}", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        logger.warn("kept warn {}", 1);
        logger.error("kept error", new IllegalStateException("boom"));

        List<JsonNode> lines = lines();
        List<String> messages = new ArrayList<String>();
        for (JsonNode line : lines) {
            messages.add(line.get("message").asText());
        }
        assertThat(messages).containsExactly("kept warn 1", "kept error");
        assertThat(lines.get(1).get("errorId").asText()).matches("[0-9a-f]{12}");
    }

    private List<JsonNode> lines() throws Exception {
        List<JsonNode> nodes = new ArrayList<JsonNode>();
        for (String line : stdout.toString(StandardCharsets.UTF_8.name()).split("\n")) {
            if (line.startsWith("{")) {
                nodes.add(JSON.readTree(line));
            }
        }
        return nodes;
    }
}
