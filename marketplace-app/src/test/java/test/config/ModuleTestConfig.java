package test.config;

import com.marketplace.identity.AuthAuditService;
import com.marketplace.identity.AuthAuditLogRepository;
import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.AuditorAware;
import com.marketplace.shared.jpa.JpaConfig;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

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
                        new MarketplaceProperties.Security.AuthServer("http://localhost:8080")
                )
        );
    }

    @Bean
    @ConditionalOnMissingBean
    UserDetailsManager userDetailsManager(DataSource dataSource) {
        JdbcUserDetailsManager manager = new JdbcUserDetailsManager(dataSource);
        manager.setUsersByUsernameQuery("select username, password, enabled from auth_users where username = ?");
        manager.setAuthoritiesByUsernameQuery("select username, authority from auth_authorities where username = ?");
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }



    @Bean
    @ConditionalOnMissingBean
    com.marketplace.identity.QrCodeService qrCodeService() {
        return new com.marketplace.identity.QrCodeService();
    }

    /**
     * Provides a stub {@link org.springframework.security.oauth2.jwt.JwtEncoder} for
     * module-scoped tests ({@code @ApplicationModuleTest} in STANDALONE mode) that load
     * the identity module without the shared-security module's {@code SecurityConfig}.
     * <p>
     * Without this bean, {@code TwoStepLoginService} (a {@code @Service} in marketplace-identity)
     * fails to initialize because its constructor parameter 8 ({@code JwtEncoder}) has no
     * qualifying bean in the module-scoped context.
     * <p>
     * The stub returns a dummy JWT — sufficient for context loading. Tests that exercise
     * actual JWT issuance use {@code @SpringBootTest} (full context) where
     * {@code SecurityConfig.jwtEncoder(JWKSource)} provides the real bean.
     * <p>
     * Reference: https://docs.spring.io/spring-modulith/reference/testing.html
     * "@ApplicationModuleTest ... bootstraps the module under test and its direct dependencies
     *  (or only the module itself, in STANDALONE mode)."
     */
    @Bean
    @ConditionalOnMissingBean
    org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder() {
        return parameters -> org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("test-jwt-token")
                .header("alg", "none")
                .claim("sub", "test")
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(3600))
                .issuer("http://localhost:8080")
                .audience(java.util.List.of("marketplace-api"))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    com.marketplace.identity.TwoStepLoginService twoStepLoginService(
            org.springframework.security.provisioning.UserDetailsManager userDetailsManager,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
            com.marketplace.identity.BruteForceProtectionService bruteForceService,
            com.marketplace.identity.MfaService mfaService,
            com.marketplace.identity.AuthAuditService auditService,
            com.marketplace.identity.UserRepository userRepository,
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
            org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder,
            com.marketplace.shared.config.MarketplaceProperties properties) {
        return new com.marketplace.identity.TwoStepLoginService(
                userDetailsManager, passwordEncoder, bruteForceService,
                mfaService, auditService, userRepository,
                redisTemplate, jwtEncoder, properties);
    }
}
