package test.config;

import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Optional;

/**
 * Shared test configuration for all {@code @ApplicationModuleTest} classes.
 *
 * <p><b>Note:</b> {@code @EnableJpaAuditing} is kept here (and also exists on
 * {@code marketplace-platform-infra}'s {@code JpaConfig}). This causes a
 * {@code BeanDefinitionOverrideException} for bean {@code jpaAuditingHandler}
 * which is masked by {@code spring.main.allow-bean-definition-overriding: true}
 * in {@code application-test.yml}. Removing it breaks {@code @CreatedDate} in
 * test entities (null {@code created_at} → {@code DataIntegrityViolationException}).
 *
 * <p><b>TODO [DEBT]:</b> Resolve this duplicate in a follow-up PR by either
 * removing {@code @EnableJpaAuditing} from {@code JpaConfig} (and keeping it
 * only here for tests) or by using {@code @ConditionalOnMissingBean} on one
 * of them.
 *
 * <p>Reference:
 * <a href="https://docs.spring.io/spring-data/jpa/reference/jpa/auditing.html">
 * Spring Data JPA — Auditing</a>
 */
@Configuration
@EnableJpaAuditing
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
                        new MarketplaceProperties.Security.AuthServer("http://localhost:8080")
                )
        );
    }
}
