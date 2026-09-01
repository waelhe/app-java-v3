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
                                new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", ""),
                                "marketplace-api"
                        ),
                        new MarketplaceProperties.Security.Session(2),
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", ""))
                )
        );
    }
}
