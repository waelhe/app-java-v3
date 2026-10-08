package com.marketplace.edge;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Edge BFF security. Each element follows an official reference:
 * <ul>
 * <li>Filter chain: the shape of Spring Boot's own default OAuth2 chain
 * ({@code authenticated()}, {@code oauth2Login}, {@code oauth2Client}); a custom
 * chain makes Boot's actuator rules back off, so health is opened with
 * {@link EndpointRequest} (Boot reference, Actuator "Security").</li>
 * <li>CSRF: {@code csrf.spa()} (Spring Security reference, CSRF for SPAs).</li>
 * <li>Logout: {@link OidcClientInitiatedLogoutSuccessHandler} (Spring Security
 * reference, OIDC Logout).</li>
 * <li>Authorized clients: the Token Relay default is an in-memory store, and the
 * Spring Cloud Gateway reference says to provide an own
 * {@link OAuth2AuthorizedClientService} for anything more robust; Spring
 * Security documents {@link JdbcOAuth2AuthorizedClientService} for that.
 * Boot's auto-configured repository and the default
 * {@code OAuth2AuthorizedClientManager} pick this bean up automatically.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class EdgeSecurityConfig {

    @Bean
    SecurityFilterChain edgeSecurityFilterChain(HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository) {
        http.authorizeHttpRequests((requests) -> requests
            .requestMatchers(EndpointRequest.to("health")).permitAll()
            .anyRequest().authenticated());
        http.oauth2Login(withDefaults());
        http.oauth2Client(withDefaults());
        http.logout((logout) -> logout
            .logoutSuccessHandler(new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository)));
        http.csrf((csrf) -> csrf.spa());
        return http.build();
    }

    @Bean
    OAuth2AuthorizedClientService authorizedClientService(JdbcOperations jdbcOperations,
            ClientRegistrationRepository clientRegistrationRepository) {
        return new JdbcOAuth2AuthorizedClientService(jdbcOperations, clientRegistrationRepository);
    }

}
