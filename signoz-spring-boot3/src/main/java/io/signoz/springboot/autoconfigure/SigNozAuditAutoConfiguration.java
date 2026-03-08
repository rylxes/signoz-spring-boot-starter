package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.audit.AuditLogAspect;
import io.signoz.springboot.audit.SigNozAuditHandler;
import io.signoz.springboot.properties.SigNozAuditProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Audit logging auto-configuration for Spring Boot 3.x.
 */
@Configuration
@ConditionalOnProperty(name = "signoz.audit.enabled", havingValue = "true", matchIfMissing = true)
public class SigNozAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect auditLogAspect(ApplicationEventPublisher eventPublisher,
                                         SigNozAuditProperties auditProps) {
        return new AuditLogAspect(eventPublisher, auditProps);
    }

    @Bean
    @ConditionalOnMissingBean
    public SigNozAuditHandler sigNozAuditHandler() {
        return new SigNozAuditHandler();
    }
}
