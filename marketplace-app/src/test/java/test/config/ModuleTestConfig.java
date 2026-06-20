package test.config;

import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.AuditorAware;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Optional;

/**
 * Shared test configuration for all {@code @ApplicationModuleTest} classes.
 *
 * <p><b>Note:</b> {@code @EnableJpaAuditing} was removed from this class because
 * {@code marketplace-platform-infra}'s {@code JpaConfig} already declares it.
 * Having both caused {@code BeanDefinitionOverrideException} for bean
 * {@code jpaAuditingHandler} when {@code AdminModuleIntegrationTest} runs in
 * {@code BootstrapMode.ALL_DEPENDENCIES} (loading both configs simultaneously).
 *
 * <p>Reference:
 * <a href="https://docs.spring.io/spring-data/jpa/reference/jpa/auditing.html">
 * Spring Data JPA — Auditing</a>
 */
@Configuration
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
