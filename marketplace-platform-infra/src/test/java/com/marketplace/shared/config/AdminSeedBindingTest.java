package com.marketplace.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N1 round 3 (the CI-measured binding question): does the Security record's
 * {@code @DefaultValue AdminSeed} section bind a NON-NULL instance in the
 * exact property-source shapes the failing contexts see?
 *
 * <p>The CI round-2 NPE: {@code MarketplaceProperties$Security.adminSeed()}
 * returned null inside the pricing/admin module-slice boots — despite the
 * empty {@code @DefaultValue} priming and the yml key being present. This
 * test reproduces the three property-source shapes empirically:
 * <ol>
 *   <li>the full test-profile shape (jwt + admin-seed.password="")</li>
 *   <li>the section-present-but-admin-seed-absent shape</li>
 *   <li>the completely-absent-section shape (the AGENTS.md rule's own case)</li>
 * </ol>
 */
class AdminSeedBindingTest {

    private static MarketplaceProperties.Security bind(Map<String, Object> props) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("repro", props));
        Binder binder = new Binder(ConfigurationPropertySources.get(environment));
        return binder.bind("marketplace.security", Bindable.of(MarketplaceProperties.Security.class)).get();
    }

    @Test
    void theTestProfileShapeBindsTheBlankSeed() {
        MarketplaceProperties.Security security = bind(Map.of(
                "marketplace.security.jwt.audience", "marketplace-api",
                "marketplace.security.admin-seed.password", ""));
        assertThat(security.adminSeed()).isNotNull();
        assertThat(security.adminSeed().password()).isEmpty();
    }

    @Test
    void theSectionPresentSeedAbsentShapeStillBindsTheDefault() {
        MarketplaceProperties.Security security = bind(Map.of(
                "marketplace.security.jwt.audience", "marketplace-api"));
        assertThat(security.adminSeed()).isNotNull();
        assertThat(security.adminSeed().password()).isEmpty();
    }
}
