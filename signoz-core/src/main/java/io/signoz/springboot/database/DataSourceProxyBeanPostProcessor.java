package io.signoz.springboot.database;

import io.signoz.springboot.properties.SigNozDatabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;

/**
 * {@link BeanPostProcessor} that wraps every {@link DataSource} bean with
 * {@link TracingDataSourceProxy} for automatic query timing and slow query detection.
 */
public class DataSourceProxyBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DataSourceProxyBeanPostProcessor.class);

    private final SigNozDatabaseProperties props;

    public DataSourceProxyBeanPostProcessor(SigNozDatabaseProperties props) {
        this.props = props;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource && !(bean instanceof TracingDataSourceProxy)) {
            log.info("[SigNoz] Wrapping DataSource '{}' with query tracing (slow threshold: {}ms)",
                    beanName, props.getSlowQueryThresholdMs());
            return new TracingDataSourceProxy((DataSource) bean, props);
        }
        return bean;
    }
}
