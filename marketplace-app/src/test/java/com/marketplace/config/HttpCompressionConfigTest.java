package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard test for HTTP response compression: the single smallest backend-side
 * lever for weak-network users. The base profile must enable the official
 * Spring Boot handle and extend (never replace) the built-in MIME list with
 * our RFC 7807 {@code application/problem+json} error bodies.
 *
 * <p>Official basis (spring-boot-web-server 4.1.1, verified by javap +
 * configuration metadata this session):
 * <ul>
 * <li>{@code ServerProperties} is {@code @ConfigurationProperties("server")}.</li>
 * <li>{@code server.compression.enabled} — "Whether response compression is
 * enabled." (default {@code false}) — wires Tomcat's
 * {@code CompressionConnectorCustomizer}.</li>
 * <li>{@code server.compression.additional-mime-types} — "Comma-separated
 * list of additional MIME types that should be compressed." (default
 * {@code []}) — extends the built-in list ({@code text/*},
 * {@code application/json}, … with a 2KB floor) instead of replacing it, so
 * a Boot default change cannot silently narrow coverage.</li>
 * </ul>
 *
 * <p>Why {@code problem+json} matters: an enabled-only flag compresses
 * success bodies ({@code application/json}) but silently skips every
 * {@code ProblemDetail} error body (401/404/503) — exactly the responses a
 * weak-network user sees most.</p>
 *
 * <p>Unit level (no Spring context), mirroring
 * {@code ForwardHeadersProdConfigTest}: the yml files are pinned here, while
 * the wire-level mechanism ({@code Content-Encoding: gzip} on a large-enough
 * body — small bodies correctly stay below the 2KB floor) is proven by live
 * curl against a booted jar.</p>
 */
class HttpCompressionConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void baseProfileEnablesCompression() throws Exception {
        assertThat(property("application.yml", "server.compression.enabled"))
                .isEqualTo("true");
    }

    @Test
    void baseProfileExtendsMimeListWithProblemJsonInsteadOfReplacingIt() throws Exception {
        assertThat(property("application.yml", "server.compression.additional-mime-types"))
                .as("RFC 7807 error bodies must ride the official extension handle")
                .contains("application/problem+json");
        // The built-in list must NOT be replaced: replacing it would drop
        // text/* and friends and drift whenever Boot changes defaults.
        assertThat(property("application.yml", "server.compression.mime-types"))
                .as("built-in MIME list must stay untouched (extension, not replacement)")
                .isNull();
    }

    @Test
    void noProfileDisablesCompression() throws Exception {
        // Compression has no trust dimension (unlike forwarded headers), so
        // every profile must behave like production. A future
        // "enabled: false" anywhere would silently reintroduce full-size
        // bodies for weak-network users.
        for (String yml : new String[] { "application-dev.yml", "application-test.yml", "application-prod.yml" }) {
            assertThat(property(yml, "server.compression.enabled"))
                    .as("%s must not disable compression", yml)
                    .isNotEqualTo("false");
        }
    }

    private String property(String yml, String key) throws java.io.IOException {
        List<PropertySource<?>> sources = loader.load(yml, new ClassPathResource(yml));
        assertThat(sources).as("%s must load", yml).isNotEmpty();
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
