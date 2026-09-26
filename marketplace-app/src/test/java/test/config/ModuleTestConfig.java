package test.config;

import com.marketplace.shared.config.MarketplaceProperties;
import com.marketplace.shared.jpa.JpaConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.AuditorAware;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Optional;

/**
 * Shared test configuration for all {@code @ApplicationModuleTest} classes.
 *
 * <p><b>Auditing in slice tests:</b> This class {@code @Import}s {@link JpaConfig}
 * (production config in {@code marketplace-platform-infra}) so that
 * {@code @EnableJpaAuditing} is active in slice test contexts. Without this import,
 * slice tests (which limit component scanning to the module's base package) would
 * not load {@code JpaConfig}, and {@code @CreatedDate}/{@code @LastModifiedDate}
 * would not be populated, causing {@code DataIntegrityViolationException} for
 * {@code NOT NULL} columns like {@code created_at}.
 *
 * <p><b>No duplicate {@code @EnableJpaAuditing}:</b> Previously this class also
 * declared {@code @EnableJpaAuditing}, which conflicted with {@code JpaConfig}'s
 * declaration (both register {@code jpaAuditingHandler} bean). The conflict was
 * masked by {@code spring.main.allow-bean-definition-overriding: true}. Now we
 * {@code @Import} the single production config instead, following the Spring Boot
 * testing guidance to reuse production {@code @Configuration} via {@code @Import}
 * rather than re-declaring {@code @Enable*} annotations.
 *
 * <p>Reference:
 * <a href="https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.user-configuration-and-slicing">
 * Spring Boot Reference — User Configuration and Slicing</a>
 * <a href="https://docs.spring.io/spring-data/jpa/reference/auditing.html">
 * Spring Data JPA — Auditing</a>
 */
@Configuration
@Import(JpaConfig.class)
public class ModuleTestConfig {

    @Bean
    WebMvcConfigurer apiVersioningConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void configureApiVersioning(ApiVersionConfigurer configurer) {
                configurer
                        .useRequestHeader("X-API-Version")
                        .setDefaultVersion("1.0");
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    AuditorAware<String> auditorAware() {
        return Optional::empty;
    }


    @Bean
    @Primary
    MarketplaceProperties marketplaceProperties() {
        return new MarketplaceProperties(
                new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                new MarketplaceProperties.Security(
                        new MarketplaceProperties.Security.Jwt(
                                new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", "", ""),
                                "marketplace-api"
                        ),
                        new MarketplaceProperties.Security.Session(2),
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", "", ""),
                                new MarketplaceProperties.Security.OAuth2.PublicClient("", "")),
                    new MarketplaceProperties.Security.Pseudonymization("", java.util.List.of()),
                        // N1 round 3 (CI-measured root): the blank seed — the module
                        // slice's designed no-op shape. This slot was NULL before: the
                        // N1 change added the AdminSeed component to the Security
                        // record, this manual construction's argument list silently
                        // shifted, and AdminUserInitializer.run() NPE'd on
                        // security().adminSeed().password() in every module-slice
                        // boot (PricingModuleIntegrationTest / AdminModuleIntegrationTest
                        // — the CI round-2 failures). Blank keeps the documented
                        // contract: not configured = the break-glass bootstrap skips.
                        new MarketplaceProperties.Security.AdminSeed(""))
        );
    }
}
