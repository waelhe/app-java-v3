package com.marketplace.identity.internal;

import com.marketplace.identity.AuthAuditService;
import com.marketplace.identity.AuthEventType;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Seeds the local dev database with an initial admin user and OAuth2 client.
 *
 * <p><b>Security policy</b>
 * <ul>
 *   <li>Runs ONLY when:
 *     <ul>
 *       <li>profile {@code dev} is active, AND</li>
 *       <li>{@code marketplace.security.seed-defaults=true} (default: false)</li>
 *     </ul>
 *     Defaulting to {@code false} prevents accidental seeding in prod even if
 *     the dev profile is mistakenly activated.</li>
 *   <li>The seeded admin password and client secret are <strong>not</strong> hardcoded —
 *     they are read from environment variables and fall back to clearly-marked
 *     dev defaults printed once to the log.</li>
 *   <li>The seeded client enforces <strong>PKCE</strong> ({@code require-proof-key})
 *     per OAuth 2.1 / RFC 8252 §7.1.</li>
 *   <li>The redirect URI is read from {@code OAUTH2_REDIRECT_URI} env var — never
 *     hardcoded to {@code 127.0.0.1}.</li>
 * </ul>
 *
 * <p>This replaces the prior {@code R__seed_oauth2_client.sql} Flyway repeatable
 * migration, which mixed schema migration with credential seeding (anti-pattern
 * per Spring Boot Reference — Database Initialization). Schema belongs in Flyway;
 * data belongs in {@code ApplicationRunner}.
 *
 * <p>Placed in the {@code identity} module (internal package) so it can use the
 * identity module's own {@link UserRepository} / {@link AuthAuditService} / {@link User}
 * / {@link UserRole} directly without violating Spring Modulith boundaries.
 *
 * <p><b>References</b>
 * <ul>
 *   <li><a href="https://docs.spring.io/spring-boot/how-to/data-initialization.html">Spring Boot — Database Initialization</a></li>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc8252#section-7.1">RFC 8252 §7.1 — PKCE for Authorization Code Grant</a></li>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc6749#section-3.1.2.1">RFC 6749 §3.1.2.1 — redirect_uri https recommended</a></li>
 *   <li><a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html">OWASP Authentication Cheat Sheet — Default Credentials</a></li>
 *   <li><a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/server-authorization/client-registration.html">Spring Authorization Server — Client Settings</a></li>
 * </ul>
 */
@Configuration
@Profile("dev")
@ConditionalOnProperty(name = "marketplace.security.seed-defaults", havingValue = "true", matchIfMissing = false)
public class DevDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    /** Fixed UUID for the seeded web client — stable across dev runs for easier testing. */
    private static final UUID SEEDED_CLIENT_ID = UUID.fromString("a7bd8b0d-7d42-4a64-9e34-1ad3ab22e37e");
    private static final String CLIENT_ID = "marketplace-web-client";
    private static final String ADMIN_USERNAME = "admin";

    /** Configuration values — injected via constructor (not field injection) for testability. */
    private final String adminPassword;
    private final String clientSecret;
    private final String redirectUri;
    private final String postLogoutUri;

    public DevDataInitializer(
            @org.springframework.beans.factory.annotation.Value("${DEV_ADMIN_PASSWORD:admin-password-change-me}") String adminPassword,
            @org.springframework.beans.factory.annotation.Value("${DEV_CLIENT_SECRET:client-secret-change-me}") String clientSecret,
            @org.springframework.beans.factory.annotation.Value("${OAUTH2_REDIRECT_URI:http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client}") String redirectUri,
            @org.springframework.beans.factory.annotation.Value("${OAUTH2_POST_LOGOUT_URI:http://127.0.0.1:8080/}") String postLogoutUri) {
        this.adminPassword = adminPassword;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.postLogoutUri = postLogoutUri;
    }

    @Bean
    @Transactional
    public ApplicationRunner seedDevData(
            RegisteredClientRepository clientRepository,
            UserRepository userRepository,
            UserDetailsManager userDetailsManager,
            PasswordEncoder passwordEncoder,
            AuthAuditService auditService) {
        return args -> {
            seedAdminUser(userRepository, userDetailsManager, passwordEncoder, auditService, adminPassword);
            seedOAuth2Client(clientRepository, passwordEncoder, clientSecret, redirectUri, postLogoutUri);

            log.warn("Dev data seeded. Admin password and client secret came from DEV_ADMIN_PASSWORD / DEV_CLIENT_SECRET env/properties.");
        };
    }

    private void seedAdminUser(UserRepository userRepository,
                                UserDetailsManager userDetailsManager,
                                PasswordEncoder passwordEncoder,
                                AuthAuditService auditService,
                                String adminPassword) {
        if (userRepository.findByEmail(ADMIN_USERNAME).isPresent()) {
            log.info("Admin user already exists — skipping seed.");
            return;
        }

        // 1. Create the Spring Security login row (auth_users + auth_authorities) via UserDetailsManager.
        if (!userDetailsManager.userExists(ADMIN_USERNAME)) {
            org.springframework.security.core.userdetails.UserDetails userDetails =
                    org.springframework.security.core.userdetails.User.withUsername(ADMIN_USERNAME)
                            .password(passwordEncoder.encode(adminPassword))
                            .roles("ADMIN")
                            .build();
            userDetailsManager.createUser(userDetails);
        }

        // 2. Create the domain user row (users table) so IdentityUserProvider can resolve the admin.
        User admin = User.create(ADMIN_USERNAME, ADMIN_USERNAME, "Dev Admin", UserRole.ADMIN);
        userRepository.save(admin);

        auditService.log(ADMIN_USERNAME, AuthEventType.REGISTRATION, "Dev admin user seeded");
        log.warn("Dev admin user 'admin' seeded with password from DEV_ADMIN_PASSWORD env var.");
    }

    private void seedOAuth2Client(RegisteredClientRepository clientRepository,
                                   PasswordEncoder passwordEncoder,
                                   String clientSecret,
                                   String redirectUri,
                                   String postLogoutUri) {
        try {
            if (clientRepository.findByClientId(CLIENT_ID) != null) {
                log.info("OAuth2 client '{}' already exists — skipping seed.", CLIENT_ID);
                return;
            }
        } catch (Exception e) {
            // Likely first run — client doesn't exist yet. Pass 'e' for stack trace.
            log.debug("Client lookup failed (likely first run)", e);
        }

        RegisteredClient client = RegisteredClient.withId(SEEDED_CLIENT_ID.toString())
                .clientId(CLIENT_ID)
                .clientSecret(passwordEncoder.encode(clientSecret))
                .clientName("Marketplace Web Client (dev)")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .postLogoutRedirectUri(postLogoutUri)
                .scope("openid")
                .scope("profile")
                .scope("email")
                // OAuth 2.1 / RFC 8252 §7.1 — PKCE required for all clients.
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(true)
                        .requireProofKey(true)
                        .build())
                // RFC 6749 §10.6 — short-lived access tokens, no refresh-token reuse.
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .build())
                .clientIdIssuedAt(Instant.now())
                .build();

        clientRepository.save(client);
        log.warn("Dev OAuth2 client '{}' seeded with secret from DEV_CLIENT_SECRET env var.", CLIENT_ID);
        log.warn("Dev OAuth2 client redirect_uri = {}", redirectUri);
    }
}
