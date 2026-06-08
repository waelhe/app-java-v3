package com.marketplace.shared.security;

import tools.jackson.databind.ObjectMapper;
import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.config.MarketplaceProperties;
import com.marketplace.shared.api.ApiProblemDetails;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.Customizer;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
public class SecurityConfig {

    private final MarketplaceProperties properties;
    private final ObjectMapper objectMapper;
    private final OAuth2ResourceServerProperties resourceServerProperties;

    public SecurityConfig(MarketplaceProperties properties,
                          ObjectMapper objectMapper,
                          OAuth2ResourceServerProperties resourceServerProperties) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.resourceServerProperties = resourceServerProperties;
    }

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
                                                               OAuth2AuthorizationService authorizationService,
                                                               OAuth2AuthorizationConsentService authorizationConsentService,
                                                               RegisteredClientRepository registeredClientRepository) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = new OAuth2AuthorizationServerConfigurer();

        http
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, authorizationServer -> authorizationServer
                        .registeredClientRepository(registeredClientRepository)
                        .authorizationService(authorizationService)
                        .authorizationConsentService(authorizationConsentService)
                        .oidc(Customizer.withDefaults())
                )
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        )
                )
                .cors(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain publicApiSecurityFilterChain(HttpSecurity http,
                                                     CorrelationIdFilter correlationIdFilter) throws Exception {
        http
                .securityMatcher(new OrRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/listings/**"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/reviews/**"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/search/**"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/actuator/health/**"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/actuator/info")
                ))
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain protectedApiSecurityFilterChain(HttpSecurity http,
                                                        CorrelationIdFilter correlationIdFilter) throws Exception {
        http
                .securityMatcher("/api/**", "/actuator/**")
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/actuator/**"))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/payments/webhooks/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemDetailAuthenticationEntryPoint())
                        .accessDeniedHandler(problemDetailAccessDeniedHandler()))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/assets/**", "/login").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .cors(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Correlation-ID"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationEntryPoint problemDetailAuthenticationEntryPoint() {
        return (request, response, ex) -> writeProblemDetail(
                response,
                ApiErrorTaxonomy.AUTHN,
                "Authentication required",
                request
        );
    }

    @Bean
    AccessDeniedHandler problemDetailAccessDeniedHandler() {
        return (request, response, ex) -> writeProblemDetail(
                response,
                ApiErrorTaxonomy.AUTHZ,
                "Access denied",
                request
        );
    }

    private void writeProblemDetail(HttpServletResponse response,
                                    ApiErrorTaxonomy taxonomy,
                                    String detail,
                                    HttpServletRequest request) throws IOException {
        String traceId = response.getHeader(CorrelationIdFilter.HEADER_NAME);

        var problemDetail = ApiProblemDetails.fromTaxonomy(taxonomy, detail, request.getRequestURI(), null, traceId);

        response.setStatus(taxonomy.statusCode().value());
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }

    @Bean
    UserDetailsManager userDetailsService(DataSource dataSource) {
        JdbcUserDetailsManager manager = new JdbcUserDetailsManager(dataSource);
        manager.setUsersByUsernameQuery("select username, password, enabled from auth_users where username = ?");
        manager.setAuthoritiesByUsernameQuery("select username, authority from auth_authorities where username = ?");
        manager.setCreateUserSql("insert into auth_users (username, password, enabled) values (?, ?, ?)");
        manager.setUpdateUserSql("update auth_users set password = ?, enabled = ? where username = ?");
        manager.setDeleteUserSql("delete from auth_users where username = ?");
        manager.setCreateAuthoritySql("insert into auth_authorities (username, authority) values (?, ?)");
        manager.setDeleteUserAuthoritiesSql("delete from auth_authorities where username = ?");
        manager.setUserExistsSql("select username from auth_users where username = ?");
        return manager;
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                    RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate,
                                                                  RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(
            ResourceLoader resourceLoader
    ) throws Exception {
        var ks = properties.security().jwt().keystore();
        String keyStorePath = ks.path();
        String keyStorePassword = ks.password();
        String keyAlias = ks.alias();
        String keyPassword = ks.keyPassword();
        if (isBlank(keyStorePath) || isBlank(keyStorePassword) || isBlank(keyAlias) || isBlank(keyPassword)) {
            KeyPair keyPair = generateRsaKey();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
            RSAKey rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        }

        KeyStore keyStore = KeyStore.getInstance("JKS");
        String resolvedLocation = keyStorePath.startsWith("classpath:") || keyStorePath.startsWith("file:")
                ? keyStorePath
                : "file:" + keyStorePath;

        try (InputStream inputStream = resourceLoader.getResource(resolvedLocation).getInputStream()) {
            keyStore.load(inputStream, keyStorePassword.toCharArray());
        }

        RSAPublicKey publicKey = (RSAPublicKey) keyStore.getCertificate(keyAlias).getPublicKey();
        Key privateKeyCandidate = keyStore.getKey(keyAlias, keyPassword.toCharArray());
        RSAPrivateKey privateKey = (RSAPrivateKey) Objects.requireNonNull(privateKeyCandidate,
                () -> "No private key found in keystore for alias " + keyAlias);

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyAlias)
                .build();

        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) throws IOException {
        OAuth2ResourceServerProperties.Jwt jwtProperties = resourceServerProperties.getJwt();
        NimbusJwtDecoder decoder = buildResourceServerJwtDecoder(jwkSource, jwtProperties);

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        String issuer = firstNonBlank(jwtProperties.getIssuerUri(), properties.security().authServer().issuer());
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));

        List<String> audiences = jwtProperties.getAudiences().isEmpty()
                ? List.of(properties.security().jwt().audience())
                : jwtProperties.getAudiences();
        validators.add(requiredAudiencesValidator(audiences));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private NimbusJwtDecoder buildResourceServerJwtDecoder(JWKSource<SecurityContext> jwkSource,
                                                           OAuth2ResourceServerProperties.Jwt jwtProperties) throws IOException {
        if (jwtProperties.getPublicKeyLocation() != null) {
            Resource publicKeyLocation = jwtProperties.getPublicKeyLocation();
            try (InputStream inputStream = publicKeyLocation.getInputStream()) {
                return withConfiguredAlgorithms(NimbusJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(inputStream)),
                        jwtProperties.getJwsAlgorithms()).build();
            }
        }

        if (!isBlank(jwtProperties.getJwkSetUri())) {
            return withConfiguredAlgorithms(NimbusJwtDecoder.withJwkSetUri(jwtProperties.getJwkSetUri()),
                    jwtProperties.getJwsAlgorithms()).build();
        }

        if (!isBlank(jwtProperties.getIssuerUri())) {
            return withConfiguredAlgorithms(NimbusJwtDecoder.withIssuerLocation(jwtProperties.getIssuerUri()),
                    jwtProperties.getJwsAlgorithms()).build();
        }

        return withConfiguredAlgorithms(NimbusJwtDecoder.withJwkSource(jwkSource), jwtProperties.getJwsAlgorithms()).build();
    }

    private static NimbusJwtDecoder.PublicKeyJwtDecoderBuilder withConfiguredAlgorithms(
            NimbusJwtDecoder.PublicKeyJwtDecoderBuilder builder,
            List<String> jwsAlgorithms) {
        if (!jwsAlgorithms.isEmpty()) {
            builder.signatureAlgorithm(SignatureAlgorithm.from(jwsAlgorithms.getFirst()));
        }
        return builder;
    }

    private static NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder withConfiguredAlgorithms(
            NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder builder,
            List<String> jwsAlgorithms) {
        if (!jwsAlgorithms.isEmpty()) {
            builder.jwsAlgorithms(algorithms -> jwsAlgorithms.stream()
                    .map(SignatureAlgorithm::from)
                    .forEach(algorithms::add));
        }
        return builder;
    }

    private static NimbusJwtDecoder.JwkSourceJwtDecoderBuilder withConfiguredAlgorithms(
            NimbusJwtDecoder.JwkSourceJwtDecoderBuilder builder,
            List<String> jwsAlgorithms) {
        if (!jwsAlgorithms.isEmpty()) {
            builder.jwsAlgorithms(algorithms -> jwsAlgorithms.stream()
                    .map(SignatureAlgorithm::from)
                    .forEach(algorithms::add));
        }
        return builder;
    }

    private static OAuth2TokenValidator<Jwt> requiredAudiencesValidator(List<String> audiences) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(audiences::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "The required audience is missing",
                null
        ));
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return isBlank(candidate) ? fallback : candidate;
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(properties.security().authServer().issuer())
                .build();
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter correlationIdFilter) {
        FilterRegistrationBean<CorrelationIdFilter> registrationBean = new FilterRegistrationBean<>(correlationIdFilter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
