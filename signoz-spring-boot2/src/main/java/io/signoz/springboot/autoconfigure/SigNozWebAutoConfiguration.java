package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.masking.MaskedArgumentAspect;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozProperties;
import io.signoz.springboot.web.HttpLoggingFilter;
import io.signoz.springboot.web.TraceIdMdcFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Auto-configures web-layer beans for Spring Boot 2.x (javax.servlet):
 * <ul>
 *   <li>{@link TraceIdMdcFilter} — injects trace IDs into MDC</li>
 *   <li>{@link HttpLoggingFilter} — logs HTTP request/response pairs</li>
 *   <li>{@link MaskedArgumentAspect} — masks {@code @Masked} method parameters</li>
 * </ul>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SigNozWebAutoConfiguration {

    private final SigNozProperties props;

    public SigNozWebAutoConfiguration(SigNozProperties props) {
        this.props = props;
    }

    @Bean
    @ConditionalOnMissingBean(TraceIdMdcFilter.class)
    public FilterRegistrationBean<TraceIdMdcFilter> traceIdMdcFilter() {
        FilterRegistrationBean<TraceIdMdcFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new TraceIdMdcFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        reg.setName("sigNozTraceIdMdcFilter");
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean(HttpLoggingFilter.class)
    public FilterRegistrationBean<HttpLoggingFilter> httpLoggingFilter(
            MaskingRegistry maskingRegistry) {
        FilterRegistrationBean<HttpLoggingFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new HttpLoggingFilter(props.getWeb(), maskingRegistry));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        reg.setName("sigNozHttpLoggingFilter");
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean
    public MaskedArgumentAspect maskedArgumentAspect(MaskingRegistry maskingRegistry) {
        return new MaskedArgumentAspect(maskingRegistry);
    }
}
