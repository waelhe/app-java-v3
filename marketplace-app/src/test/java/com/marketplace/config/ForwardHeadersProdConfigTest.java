package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for Phase A (host-neutral readiness) of the adopted client &amp;
 * hosting strategy plan ({@code docs/security/client-hosting-strategy-plan.md}
 * §7): the documented {@code server.forward-headers-strategy} debt
 * (consistency matrix §8, item 10) is closed in the production profile —
 * and only there.
 *
 * <p>Official basis (cached verbatim sources under
 * {@code scripts/prod-design-docs/src-verify/}):
 * <ul>
 * <li>Spring Boot 4.1 reference, "Running Behind a Front-end Proxy Server":
 * "If this is not enough, Spring Framework provides a ForwardedHeaderFilter
 * for the servlet stack … You can use them in your application by setting
 * {@code server.forward-headers-strategy} to FRAMEWORK."</li>
 * <li>Spring Security 7.1.1 reference (features/exploits/http): forwarded
 * headers must only be applied when a <em>trusted</em> edge proxy strips
 * client-supplied values — hence dev/test/base profiles must leave the
 * strategy unset (NONE).</li>
 * <li>{@code TomcatServerProperties#getRedirectContextRoot()} javadoc:
 * "When using SSL terminated at a proxy, this property should be set to
 * false."</li>
 * <li>{@code OAuth2AuthorizationServerProperties} (Boot 4.1.1, prefix
 * {@code spring.security.oauth2.authorizationserver}): the production profile
 * must bind {@code AUTH_SERVER_ISSUER} without a default so the base-profile
 * default ({@code http://localhost:8080}) can never leak into production —
 * the same fail-fast contract as {@code DB_PASSWORD},
 * {@code JWT_KEYSTORE_*} and {@code CORS_ALLOWED_ORIGINS}.</li>
 * </ul>
 *
 * <p>Unit level (no Spring context), mirroring
 * {@code JwkSourceProdHardeningTest}: the yml files are pinned here, while
 * the wire-level mechanism of the activated filter is pinned by
 * {@code ForwardedHeaderFilterBehaviorTest}.</p>
 */
class ForwardHeadersProdConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void prodProfileEnablesFrameworkStrategyAndDisablesTomcatContextRootRedirect() throws Exception {
        assertThat(property("application-prod.yml", "server.forward-headers-strategy"))
                .isEqualTo("FRAMEWORK");
        assertThat(property("application-prod.yml", "server.tomcat.redirect-context-root"))
                .isEqualTo("false");
    }

    @Test
    void nonProdProfilesLeaveStrategyUnsetSoForwardedHeadersAreIgnored() throws Exception {
        // Trust boundary: only prod runs behind a trusted TLS-terminating proxy
        // that strips client-supplied Forwarded / X-Forwarded-* values.
        for (String yml : new String[] { "application.yml", "application-dev.yml", "application-test.yml" }) {
            assertThat(property(yml, "server.forward-headers-strategy"))
                    .as("%s must not enable forwarded-header processing", yml)
                    .isNull();
        }
    }

    @Test
    void prodProfileBindsIssuerWithoutDefaultToFailFast() throws Exception {
        // Phase A prod audit finding: issuer was the only base-profile default
        // still leaking into production (application.yml:
        // AUTH_SERVER_ISSUER:http://localhost:8080).
        assertThat(property("application-prod.yml", "spring.security.oauth2.authorizationserver.issuer"))
                .isEqualTo("${AUTH_SERVER_ISSUER}");
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
