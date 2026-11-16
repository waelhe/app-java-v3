package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the S10 health architecture fix, pinning the two YAML
 * decisions that keep the aggregate health honest and diagnosable:
 *
 * <p>(1) <b>mail does not gate platform health</b> —
 * {@code management.health.mail.enabled=false} in the base profile. Root
 * cause this guards (measured 2026-09-26): the auto-configured
 * {@code MailHealthIndicator} dragged the aggregate
 * {@code /actuator/health} to 503 DOWN (~5.9s response — the 5s SMTP
 * connectiontimeout signature; readiness db,redis,diskSpace and liveness
 * ping stayed UP) because the prod SMTP host is unreachable from the
 * Railway runtime. Email is an auxiliary, best-effort channel in this
 * architecture: sends are {@code @Observed("email.send")}, failures are
 * logged, and outage observability belongs to the metrics pipeline — not
 * to a synchronous SMTP connect embedded in every health call. Official
 * switch: Spring Boot Reference, Actuator › Endpoints ›
 * "Auto-configured HealthIndicators" —
 * {@code management.health.<key>.enabled} per indicator key.
 *
 * <p>(2) <b>the aggregate verdict is not blind</b> — prod pins
 * {@code show-details/show-components: when-authorized} (official
 * semantics: components and details are shown only to authenticated
 * users; anonymous callers still receive the bare status). The pre-fix
 * {@code never/never} reported 503 DOWN with zero diagnostic surface: the
 * failing contributor could only be identified by its latency signature.
 *
 * <p>Unit level (no Spring context), mirroring
 * {@code EventStalenessProdConfigTest}: the yml files are pinned here so
 * neither decision can silently regress.
 */
class HealthProdConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void baseProfileKeepsMailOutOfPlatformHealth() throws Exception {
        // Auxiliary best-effort channel: must not drag the aggregate DOWN.
        assertThat(property("application.yml", "management.health.mail.enabled"))
                .isEqualTo("false");
    }

    @Test
    void prodProfileUnblindsHealthForAuthenticatedOperators() throws Exception {
        assertThat(property("application-prod.yml", "management.endpoint.health.show-details"))
                .isEqualTo("when-authorized");
        assertThat(property("application-prod.yml", "management.endpoint.health.show-components"))
                .isEqualTo("when-authorized");
    }

    @Test
    void prodCriticalPathHealthGroupsArePinned() throws Exception {
        // The critical-path definition must stay exactly this: readiness =
        // the request-serving dependencies (db, redis, diskSpace); liveness =
        // ping. Platform subsystems with their own gauge/alert pipeline
        // (modulithEventBus) stay in the aggregate, NOT in readiness — a
        // stale event backlog must not evict a healthy instance from the
        // load balancer.
        assertThat(property("application-prod.yml", "management.endpoint.health.group.readiness.include"))
                .isEqualTo("db,redis,diskSpace");
        assertThat(property("application-prod.yml", "management.endpoint.health.group.liveness.include"))
                .isEqualTo("ping");
    }

    @Test
    void noHealthGroupPullsMailBackIn() throws Exception {
        // Groups can include/exclude indicators (official Health Groups
        // feature); none of ours may reference mail — that would re-couple
        // an auxiliary channel to a probe surface.
        for (String yml : new String[]{"application.yml", "application-prod.yml"}) {
            for (String group : new String[]{"readiness", "liveness"}) {
                String include = property(yml, "management.endpoint.health.group." + group + ".include");
                if (include != null) {
                    assertThat(include.split(","))
                            .as("%s group %s must not include mail", yml, group)
                            .doesNotContain("mail");
                }
            }
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
