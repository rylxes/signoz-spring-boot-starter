package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.database.DataSourceProxyBeanPostProcessor;
import io.signoz.springboot.properties.SigNozDatabaseProperties;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * Static, with the properties resolved lazily: a post-processor is instantiated before other
     * beans, and an instance method or an eagerly injected properties bean would force this class
     * and {@link SigNozDatabaseProperties} to be created early too, which Spring reports at startup
     * as beans "not eligible for getting processed by all BeanPostProcessors".
     */
    @Bean
    @ConditionalOnMissingBean
    public static DataSourceProxyBeanPostProcessor dataSourceProxyBeanPostProcessor(
            ObjectProvider<SigNozDatabaseProperties> props) {
        return new DataSourceProxyBeanPostProcessor(props::getObject);
    }
}
