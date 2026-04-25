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
 * <p>Controllers can declare versioned mappings using the {@code version} attribute:
 * <pre>
 *   &#64;GetMapping(version = "1.0")
 *   &#64;GetMapping(version = "1.1+")  // baseline: matches 1.1 and later
 * </pre>
 *
 * <p>Supported version formats: semantic (major.minor.patch).
 * Missing version header results in a 400 response by default.
 * Deprecation hints are sent via RFC 9745 / RFC 8594 headers when configured.
 *
 * @see ApiVersionConfigurer#useRequestHeader(String)
 * @see WebMvcConfigurer#configureApiVersioning(ApiVersionConfigurer)
 */
@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer<?> configurer) {
        configurer.useRequestHeader("X-API-Version");
    }
}
