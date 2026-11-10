package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for finding W2 of the 2026-11-10 adversarial behavioral review
 * ({@code docs/governance/adversarial-behavioral-review-2026-11-10.md}):
 * {@code IdentityMailProperties}' own javadoc claimed "production binds its
 * own public origin", yet no profile document ever pinned
 * {@code marketplace.identity.mail-base-url} — the record's
 * {@code @DefaultValue("http://localhost:3000")} therefore flowed into every
 * deployed composition, and password-reset / email-verification deep links
 * pointed at localhost.
 *
 * <p>Closed by the documented fail-fast contract for client-facing
 * production origins — the exact pattern {@code AUTH_SERVER_ISSUER},
 * {@code CORS_ALLOWED_ORIGINS} and {@code MAIL_FROM} already apply in
 * {@code application-prod.yml} (pinned by
 * {@code ForwardHeadersProdConfigTest#prodProfileBindsIssuerWithoutDefaultToFailFast}):
 * the deployable profiles bind {@code ${MARKETPLACE_IDENTITY_MAIL_BASE_URL}}
 * with no default, so an unset variable fails at placeholder resolution
 * before the app ever mints a mail link, instead of silently mailing the
 * development origin. Staging carries the same no-default binding at parity
 * (it submits real mail through the real production path — the staging mail
 * contract in {@code application-staging.yml}); the deployment-target side
 * of the contract is enforced by {@code PlaceholderTargetMatrixGuardTest}
 * (D10/G2: the no-default name must be declared in
 * {@code .railway/railway.ts}).
 *
 * <p>Unit level (no Spring context), mirroring
 * {@code ForwardHeadersProdConfigTest}: the yml documents are pinned here,
 * while the record's defaulting behavior is pinned by the identity module's
 * own {@code IdentityMailProperties} tests.</p>
 */
class IdentityMailBaseUrlProdConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void prodProfileBindsIdentityMailBaseUrlWithoutDefaultToFailFast() throws Exception {
        // W2: the localhost:3000 development default must never leak into a
        // production composition — the binding carries no default so an unset
        // MARKETPLACE_IDENTITY_MAIL_BASE_URL fails at placeholder resolution.
        assertThat(property("application-prod.yml", "marketplace.identity.mail-base-url"))
                .isEqualTo("${MARKETPLACE_IDENTITY_MAIL_BASE_URL}");
    }

    @Test
    void stagingProfileBindsIdentityMailBaseUrlWithoutDefaultToFailFast() throws Exception {
        // Staging parity: the sandbox mails real account links through the
        // real production path, so its link origin is exactly as mandatory
        // as production's.
        assertThat(property("application-staging.yml", "marketplace.identity.mail-base-url"))
                .isEqualTo("${MARKETPLACE_IDENTITY_MAIL_BASE_URL}");
    }

    @Test
    void nonDeployableProfilesLeaveTheKeyUnsetSoTheDevDefaultFlowsFromTheRecord() throws Exception {
        // Trust boundary: localhost:3000 is the development client origin only.
        // It must flow solely from IdentityMailProperties' @DefaultValue — no
        // non-deployable profile document may pin the client link origin.
        for (String yml : new String[] { "application.yml", "application-dev.yml", "application-test.yml" }) {
            assertThat(property(yml, "marketplace.identity.mail-base-url"))
                    .as("%s must not pin the client link origin", yml)
                    .isNull();
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
