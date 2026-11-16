package com.marketplace.shared.web;

import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.accept.StandardApiVersionDeprecationHandler;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.util.Map;

/**
 * Configures API versioning using Spring Framework 7 built-in support.
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
 * <p>Controllers declare versioned mappings using the {@code version} attribute:
 * <pre>
 *   &#64;GetMapping(version = "1.0")
 *   &#64;GetMapping(version = "1.1+")  // baseline: matches 1.1 and later
 * </pre>
 *
 * <p>Supported version formats: semantic (major.minor.patch).
 *
 * <p><b>Deprecation policy (compliance plan B.1 — the deprecation half):</b>
 * when {@code marketplace.api-versioning.deprecations} carries entries, the
 * official {@link StandardApiVersionDeprecationHandler} is registered and
 * sends the {@code Deprecation} (RFC 9745) and {@code Sunset} (RFC 8594)
 * response headers — and the {@code Link} header when a migration document
 * is configured — to callers of those versions ("can set the 'Deprecation'
 * 'Sunset' headers and 'Link' headers as defined in RFC 9745 and RFC 8594" —
 * Spring Framework, Web on Servlet Stack › API Versioning ›
 * ApiVersionDeprecationHandler). An empty map registers no handler at all:
 * the live {@code 1.0} surface stays hint-free, and deprecating a version
 * when a newer one ships is an environment change, never a rebuild.
 *
 * @see ApiVersionConfigurer#useRequestHeader(String)
 * @see ApiVersionConfigurer#setDefaultVersion(String)
 * @see ApiVersionConfigurer#setDeprecationHandler(org.springframework.web.accept.ApiVersionDeprecationHandler)
 * @see WebMvcConfigurer#configureApiVersioning(ApiVersionConfigurer)
 */
@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    private final MarketplaceProperties properties;

    public ApiVersioningConfig(MarketplaceProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                .useRequestHeader("X-API-Version")
                .setDefaultVersion("1.0");
        registerDeprecationPolicy(configurer, properties.apiVersioning().deprecations());
    }

    /**
     * Maps the configured deprecation entries onto the official
     * {@link StandardApiVersionDeprecationHandler.VersionSpec} DSL, one
     * {@code configureVersion} call per deprecated version. Absent date/link
     * components are simply not set on the spec — the handler then emits only
     * the headers that carry information, exactly as configured.
     */
    private void registerDeprecationPolicy(ApiVersionConfigurer configurer,
                                           Map<String, MarketplaceProperties.ApiVersioning.Deprecation> deprecations) {
        if (deprecations.isEmpty()) {
            return; // the official default: no handler, no hints, zero change
        }
        StandardApiVersionDeprecationHandler handler = new StandardApiVersionDeprecationHandler();
        deprecations.forEach((version, spec) -> {
            StandardApiVersionDeprecationHandler.VersionSpec versionSpec = handler.configureVersion(version);
            if (spec.deprecationDate() != null) {
                versionSpec.setDeprecationDate(spec.deprecationDate());
            }
            if (spec.sunsetDate() != null) {
                versionSpec.setSunsetDate(spec.sunsetDate());
            }
            if (!spec.link().isBlank()) {
                versionSpec.setDeprecationLink(URI.create(spec.link()));
            }
        });
        configurer.setDeprecationHandler(handler);
    }
}
