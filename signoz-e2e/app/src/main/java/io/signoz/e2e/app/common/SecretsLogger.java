package io.signoz.e2e.app.common;

import io.signoz.springboot.annotation.SigNozLog;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/** Logs sensitive values every way an application might, for the masking scenarios. */
@SigNozLog
@Component
public class SecretsLogger {

    public void logSecrets(String canary) {
        log.info("login attempt password={}", canary);
        log.info("payload {}", "{\"user\":\"alice\",\"password\":\"" + canary + "\",\"ok\":true}");
        log.info("bearer header Authorization: Bearer {}", canary);
        log.info("card 4111 1111 1111 1111 and ssn 123-45-6789");
        log.info("pci payload {}", "{\"cardPan\":\"5061051234566567\",\"cvv\":\"" + canary + "\"}");
        if (ClassUtils.isPresent("ch.qos.logback.classic.Logger", null)) {
            log.info("structured {}", StructuredArguments.kv("api_token", canary));
        }
        MDC.put("client_secret", canary);
        try {
            log.info("with a secret in the MDC");
        } finally {
            MDC.remove("client_secret");
        }
    }

    public void logLevels(String marker, int infos) {
        for (int i = 0; i < infos; i++) {
            log.info("{} info {}", marker, i);
        }
        log.warn("{} warn", marker);
        log.error("{} error", marker);
    }

    public void logFailure(RuntimeException e) {
        log.error("operation failed", e);
    }
}
