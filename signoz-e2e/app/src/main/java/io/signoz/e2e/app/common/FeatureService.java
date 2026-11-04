package io.signoz.e2e.app.common;

import io.signoz.springboot.annotation.AlertOnFailure;
import io.signoz.springboot.annotation.AuditLog;
import io.signoz.springboot.annotation.Masked;
import io.signoz.springboot.annotation.SigNozLog;
import io.signoz.springboot.annotation.Timed;
import io.signoz.springboot.annotation.Traced;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Methods carrying the starter's annotations. {@code @SigNozLog} supplies {@code log}: if the
 * annotation processor did not run, this class would not compile.
 */
@SigNozLog
@Service
public class FeatureService {

    private final Observations observations;

    public FeatureService(Observations observations) {
        this.observations = observations;
    }

    @Traced(operationName = "e2e.traced", tags = {"feature=traced"})
    public String traced() {
        log.info("inside the traced method");
        return "traced";
    }

    @AuditLog(action = "E2E_LOGIN", resourceType = "User", resourceId = "#user")
    public String login(String user, @Masked String password) {
        // The business logic receives the real value.
        return "ok:" + user + ":" + password.length();
    }

    @Timed("e2e.timed")
    public String timed(long sleepMs) throws InterruptedException {
        Thread.sleep(sleepMs);
        return "slept " + sleepMs;
    }

    @AlertOnFailure(metric = "e2e.failures")
    public void fail() {
        throw new IllegalStateException("e2e deliberate failure");
    }

    @Async
    public void async(String label) {
        log.info("async work {}", label);
        observations.record("async", Collections.singletonMap("label", label));
    }
}
