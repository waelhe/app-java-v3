package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the N1 admin-seed hardening — pins the YAML wiring of the
 * break-glass administrator's password channel:
 *
 * <ul>
 *   <li>base profile: {@code marketplace.security.admin-seed.password} binds
 *       from {@code ADMIN_SEED_PASSWORD} with an empty default (blank outside
 *       prod = the documented development fallback inside
 *       {@code AdminUserInitializer});</li>
 *   <li>prod profile: NO default — an unset {@code ADMIN_SEED_PASSWORD} fails
 *       at placeholder resolution, the first fail-fast defense layer (the
 *       same binding contract as {@code OAUTH_CLIENT_SECRET} /
 *       {@code JWT_KEYSTORE_PASSWORD}).</li>
 * </ul>
 *
 * <p>Root cause this guards: the pre-fix source of the admin credential was a
 * fixed bcrypt hash inside {@code R__seed_oauth2_client.sql} — a secret burned
 * into the repository and the jar for every environment, with only a comment
 * asking operators to rotate it. Unit level (no Spring context), mirroring
 * {@code EventStalenessProdConfigTest}: the yml files are pinned here.
 */
class AdminSeedProdConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void baseProfileBindsAdminSeedPasswordWithEmptyDefault() throws Exception {
        assertThat(property("application.yml", "marketplace.security.admin-seed.password"))
                .isEqualTo("${ADMIN_SEED_PASSWORD:}");
    }

    @Test
    void prodProfileBindsAdminSeedPasswordWithoutDefault() throws Exception {
        // No trailing ":" default — unset ADMIN_SEED_PASSWORD must fail at
        // placeholder resolution instead of silently binding blank.
        assertThat(property("application-prod.yml", "marketplace.security.admin-seed.password"))
                .isEqualTo("${ADMIN_SEED_PASSWORD}");
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
