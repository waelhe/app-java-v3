package com.marketplace.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the constructor-binding contract of {@link MarketplaceProperties}: nested record
 * components primed with an empty {@link org.springframework.boot.context.properties.bind.DefaultValue}
 * are always bound to a non-null instance, even when the corresponding keys are absent.
 *
 * <p>This is exactly the regression that failed {@code MarketplaceApplicationTest.contextLoads}
 * in CI: {@code marketplace.security.oauth2} is not defined outside production, yet the
 * {@link com.marketplace.shared.security.OAuth2ClientSecretInitializer} dereferences it.
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.constructor-binding">
 *      Spring Boot — Type-safe Configuration Properties — Constructor binding — @DefaultValue</a>
 */
class MarketplacePropertiesBindingTest {

    @Test
    void bindsAbsentOauth2SectionToNonNullDefaults() {
        Map<String, Object> source = Map.of("marketplace.security.session.max-sessions", "2");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        assertThat(properties.security()).isNotNull();
        MarketplaceProperties.Security.OAuth2 oauth2 = properties.security().oauth2();
        assertThat(oauth2).isNotNull();
        assertThat(oauth2.client()).isNotNull();
        assertThat(oauth2.client().clientId()).isEmpty();
        assertThat(oauth2.client().secret()).isEmpty();
    }
}