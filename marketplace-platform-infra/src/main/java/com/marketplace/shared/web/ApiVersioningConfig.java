package com.marketplace.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures API versioning using Spring Framework 7.0 built-in support.
 *
 * <p>Official reference:
 * <a href="https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/api-version.html">
 * Spring Framework — API Versioning</a>
 *
 * <p>Resolves API version from the {@code X-API-Version} request header
 * via {@link ApiVersionConfigurer#useRequestHeader(String)}.
 *
 * <p>A default version of {@code "1.0"} is set via
 * {@link ApiVersionConfigurer#setDefaultVersion(String)} so that requests
 * without an {@code X-API-Version} header are routed to the current
 * API version rather than being rejected with a 400. This preserves
 * backward compatibility with existing clients during the transition
 * to versioned APIs.
 *
 * <p>Controllers can declare versioned mappings using the {@code version} attribute:
 * <pre>
 *   &#64;GetMapping(version = "1.0")
 *   &#64;GetMapping(version = "1.1+")  // baseline: matches 1.1 and later
 * </pre>
 *
 * <p>Supported version formats: semantic (major.minor.patch).
 * Deprecation hints are sent via RFC 9745 / RFC 8594 headers when configured.
 *
 * @see ApiVersionConfigurer#useRequestHeader(String)
 * @see ApiVersionConfigurer#setDefaultVersion(String)
 * @see WebMvcConfigurer#configureApiVersioning(ApiVersionConfigurer)
 */
@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer<?> configurer) {
        configurer
                .useRequestHeader("X-API-Version")
                .setDefaultVersion("1.0");
    }
}
