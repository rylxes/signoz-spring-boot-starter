package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.diagnostics.SigNozStartupDiagnostics;
import io.signoz.springboot.properties.SigNozProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures the startup diagnostics bean for Spring Boot 3.x.
 *
 * <p>Registers {@link SigNozStartupDiagnostics} which logs a configuration
 * summary once all beans have been initialized.
 */
@Configuration
public class SigNozDiagnosticsAutoConfiguration {

    /**
     * Creates the {@link SigNozStartupDiagnostics} bean.
     *
     * @param props the root SigNoz configuration properties
     * @return a new diagnostics instance
     */
    @Bean
    @ConditionalOnMissingBean
    public SigNozStartupDiagnostics sigNozStartupDiagnostics(SigNozProperties props) {
        return new SigNozStartupDiagnostics(props);
    }
}
