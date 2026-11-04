package io.signoz.e2e.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A demo service running in its own JVM, the way it would in production: its own classpath, its
 * own logging configuration, and, when asked, the OpenTelemetry Java agent. Console output is
 * captured line by line (and copied to {@code target/e2e-logs}) so tests can assert on it.
 */
public final class ServiceProcess implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final int port;
    private final Process process;
    private final List<String> lines = new CopyOnWriteArrayList<String>();
    private final Thread pump;

    private ServiceProcess(String name, int port, List<String> command, Path logFile) throws IOException {
        this.name = name;
        this.port = port;
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        // Inherited OTEL_* variables would reconfigure the agent or SDK under test.
        pb.environment().keySet().removeIf(k -> k.startsWith("OTEL_"));
        this.process = pb.start();
        Files.createDirectories(logFile.getParent());
        final PrintWriter copy = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8), true);
        copy.println("# " + String.join(" ", command));
        this.pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                    copy.println(line);
                }
            } catch (IOException ignored) {
                // process ended
            } finally {
                copy.close();
            }
        }, "e2e-output-" + name);
        pump.setDaemon(true);
        pump.start();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Every console line so far. */
    public List<String> lines() {
        return Collections.unmodifiableList(new ArrayList<String>(lines));
    }

    /** Console lines that are JSON objects, parsed. Non-JSON lines are skipped. */
    public List<Map<String, Object>> jsonLines() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("{") && t.endsWith("}")) {
                try {
                    out.add(JSON.readValue(t, new TypeReference<LinkedHashMap<String, Object>>() { }));
                } catch (IOException ignored) {
                    // not JSON after all
                }
            }
        }
        return out;
    }

    /** Waits for a console line matching the predicate and returns it. */
    public String awaitLine(Predicate<String> match, long timeoutMs, String description) {
        return Await.until(() -> {
            for (String line : lines) {
                if (match.test(line)) {
                    return line;
                }
            }
            return null;
        }, timeoutMs, name + ": " + description + "\n--- recent output ---\n" + tail(40));
    }

    /** Waits for a JSON log line matching the predicate and returns it. */
    public Map<String, Object> awaitJson(Predicate<Map<String, Object>> match, long timeoutMs, String description) {
        return Await.until(() -> {
            for (Map<String, Object> line : jsonLines()) {
                if (match.test(line)) {
                    return line;
                }
            }
            return null;
        }, timeoutMs, name + ": " + description + "\n--- recent output ---\n" + tail(40));
    }

    public String tail(int n) {
        List<String> all = lines();
        return String.join("\n", all.subList(Math.max(0, all.size() - n), all.size()));
    }

    /** Waits until {@code GET /e2e/ping} answers, or fails with the process output. */
    public ServiceProcess awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError(name + " exited during startup (code " + process.exitValue() + ")\n" + tail(80));
            }
            try {
                if (Http.get(baseUrl() + "/e2e/ping").status == 200) {
                    return this;
                }
            } catch (IOException notYet) {
                // keep waiting
            }
            sleep(250);
        }
        throw new AssertionError(name + " was not ready after " + timeoutMs + "ms\n" + tail(80));
    }

    /**
     * Stops the service the way an orchestrator would (SIGTERM), so Spring closes the context and
     * the exporters flush. Falls back to a forced kill.
     */
    public void stopGracefully() {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            pump.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int exitCode() {
        return process.isAlive() ? Integer.MIN_VALUE : process.exitValue();
    }

    @Override
    public void close() {
        stopGracefully();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Builds and starts a {@link ServiceProcess}. */
    public static final class Builder {
        private final String name;
        private String mainClass;
        private List<String> classpath = new ArrayList<String>();
        private final List<String> excludedJars = new ArrayList<String>();
        private final List<String> jvmArgs = new ArrayList<String>();
        private final Map<String, String> properties = new LinkedHashMap<String, String>();
        private int port;

        private Builder(String name) {
            this.name = name;
        }

        public Builder mainClass(String mainClass) {
            this.mainClass = mainClass;
            return this;
        }

        public Builder classpath(List<String> entries) {
            this.classpath = new ArrayList<String>(entries);
            return this;
        }

        /** Leaves out classpath jars whose file name starts with one of these prefixes. */
        public Builder excludeJars(String... fileNamePrefixes) {
            Collections.addAll(excludedJars, fileNamePrefixes);
            return this;
        }

        public Builder jvmArg(String arg) {
            jvmArgs.add(arg);
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return property("server.port", String.valueOf(port));
        }

        public Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        public Builder properties(Map<String, String> values) {
            properties.putAll(values);
            return this;
        }

        public ServiceProcess start(Path logDir) {
            List<String> cp = new ArrayList<String>();
            for (String entry : classpath) {
                String file = new File(entry).getName();
                boolean excluded = false;
                for (String prefix : excludedJars) {
                    if (file.startsWith(prefix)) {
                        excluded = true;
                        break;
                    }
                }
                if (!excluded) {
                    cp.add(entry);
                }
            }
            List<String> command = new ArrayList<String>();
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Xmx384m");
            command.addAll(jvmArgs);
            command.add("-cp");
            command.add(String.join(File.pathSeparator, cp));
            command.add(mainClass);
            for (Map.Entry<String, String> p : properties.entrySet()) {
                command.add("--" + p.getKey() + "=" + p.getValue());
            }
            try {
                return new ServiceProcess(name, port, command, logDir.resolve(name + ".log"));
            } catch (IOException e) {
                throw new IllegalStateException("Could not start " + name, e);
            }
        }
    }
}
