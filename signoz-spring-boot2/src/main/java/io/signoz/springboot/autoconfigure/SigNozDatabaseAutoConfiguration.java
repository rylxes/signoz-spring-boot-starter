package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.database.DataSourceProxyBeanPostProcessor;
import io.signoz.springboot.properties.SigNozDatabaseProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures slow query detection by wrapping DataSource beans.
 */
@Configuration
@ConditionalOnProperty(name = "signoz.database.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(name = "javax.sql.DataSource")
public class SigNozDatabaseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataSourceProxyBeanPostProcessor dataSourceProxyBeanPostProcessor(SigNozDatabaseProperties props) {
        return new DataSourceProxyBeanPostProcessor(props);
    }
}
