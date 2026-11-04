package io.signoz.springboot.database;

import io.signoz.springboot.properties.SigNozDatabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.util.function.Supplier;

/**
 * {@link BeanPostProcessor} that wraps every {@link DataSource} bean with
 * {@link TracingDataSourceProxy} for automatic query timing and slow query detection.
 */
public class DataSourceProxyBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DataSourceProxyBeanPostProcessor.class);

    private final Supplier<SigNozDatabaseProperties> props;

    public DataSourceProxyBeanPostProcessor(SigNozDatabaseProperties props) {
        this(() -> props);
    }

    /**
     * Resolves the properties on first use. A post-processor is created before ordinary beans,
     * so anything it takes eagerly is created early too, and Spring then logs that those beans
     * "are not eligible for getting processed by all BeanPostProcessors".
     */
    public DataSourceProxyBeanPostProcessor(Supplier<SigNozDatabaseProperties> props) {
        this.props = props;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource && !(bean instanceof TracingDataSourceProxy)) {
            SigNozDatabaseProperties properties = props.get();
            log.info("[SigNoz] Wrapping DataSource '{}' with query tracing (slow threshold: {}ms)",
                    beanName, properties.getSlowQueryThresholdMs());
            return new TracingDataSourceProxy((DataSource) bean, properties);
        }
        return bean;
    }
}
