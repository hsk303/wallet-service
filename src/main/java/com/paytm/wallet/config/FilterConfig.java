package com.paytm.wallet.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filters are registered here (rather than via @Component on the filter
 * classes) so the order is explicit and unambiguous: correlation id must be
 * established before auth runs, so that even a 401 response is logged with
 * a correlation id.
 */
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(1);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<BearerAuthFilter> bearerAuthFilterRegistration() {
        FilterRegistrationBean<BearerAuthFilter> registration =
                new FilterRegistrationBean<>(new BearerAuthFilter());
        registration.setOrder(2);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
