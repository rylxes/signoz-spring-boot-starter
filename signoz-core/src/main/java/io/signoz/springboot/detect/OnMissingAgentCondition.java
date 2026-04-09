package io.signoz.springboot.detect;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring {@link Condition} that matches only when the OpenTelemetry Java Agent
 * is <em>not</em> present. Use with {@code @Conditional(OnMissingAgentCondition.class)}
 * to skip bean creation when the agent handles OTLP export.
 */
public class OnMissingAgentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !AgentDetector.isAgentPresent();
    }
}
