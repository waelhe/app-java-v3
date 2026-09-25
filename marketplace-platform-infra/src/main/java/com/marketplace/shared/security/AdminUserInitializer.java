package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Bootstraps the break-glass {@code admin} user from external configuration
 * (the {@code ADMIN_SEED_PASSWORD} environment variable) into the
 * {@code auth_users}/{@code auth_authorities} tables at startup — the same
 * env-to-DB convergence architecture as {@link OAuth2ClientSecretInitializer},
 * and the fix for the N1 finding: the pre-fix source was a fixed bcrypt hash
 * burned into the repeatable migration {@code R__seed_oauth2_client.sql}, a
 * credential shipped in the repository and the jar for every environment.
 *
 * <p>This is the single official mutation path for the seeded user:
 * {@link UserDetailsManager} (Spring Security's provisioning API — the
 * {@code JdbcUserDetailsManager} bean from {@code SecurityConfig} wired to
 * the custom {@code auth_*} SQL). No hand-written SQL touches the tables.
 *
 * <p><b>Converge-on-boot:</b> the seeded user is environment-owned break-glass
 * tooling — the stored row is never a source of truth. When absent it is
 * <em>created</em> ({@code ROLE_ADMIN}); when present it is compared against
 * the derived definition (password via {@link PasswordEncoder#matches},
 * authority set, enabled flag) and {@code updateUser} converges any
 * difference. Rotation is therefore an environment change, exactly like
 * {@code OAUTH_CLIENT_SECRET}. Deliberate consequence (documented): an
 * operator who hand-edits this row fights the next boot — additional or
 * customized administrative accounts belong to the future user-management
 * surface (roadmap B1), not to the break-glass seed.
 *
 * <p><b>Fail-fast by profile:</b> in {@code prod} a blank password fails
 * startup with the operational message (the yml binding without a default is
 * the first defense layer — identical to {@code OAUTH_CLIENT_SECRET}). In
 * every other profile a blank value falls back to the documented development
 * constant, mirroring the ephemeral-RSA development fallback of
 * {@code SecurityConfig#jwkSource}: local convenience, never production.
 *
 * <p><b>Idempotence guard:</b> {@code updateUser} runs only when the derived
 * definition differs from the stored row, so a matching row is a no-op and
 * concurrent replicas converge without rewriting identical rows. The
 * concurrent-bootstrap race (two replicas both insert) loses cleanly on the
 * unique username index and converges onto the winner's row — the same
 * CodeRabbit #241 pattern the client initializer applies.
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/jdbc.html">Spring Security — JdbcUserDetailsManager</a>
 */
@Component
public class AdminUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminUserInitializer.class);

    private static final Profiles PROD_PROFILE = Profiles.of("prod");

    /** The break-glass identity: referenced by the seed contract only. */
    static final String ADMIN_USERNAME = "admin";

    /**
     * Development-only fallback when {@code ADMIN_SEED_PASSWORD} is blank
     * outside the {@code prod} profile — the same category as the ephemeral
     * RSA signing key: local convenience, documented, never production.
     */
    static final String DEV_DEFAULT_PASSWORD = "admin-dev";

    private final MarketplaceProperties properties;
    private final UserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public AdminUserInitializer(MarketplaceProperties properties,
                                UserDetailsManager userDetailsManager,
                                PasswordEncoder passwordEncoder,
                                Environment environment) {
        this.properties = properties;
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String rawPassword = properties.security().adminSeed().password();
        boolean prodProfile = environment.acceptsProfiles(PROD_PROFILE);

        if (!StringUtils.hasText(rawPassword)) {
            if (prodProfile) {
                throw new IllegalStateException(
                        "marketplace.security.admin-seed.password must be configured in production"
                                + " (ADMIN_SEED_PASSWORD) — the fixed-hash migration seed is retired (N1)");
            }
            rawPassword = DEV_DEFAULT_PASSWORD;
            log.warn("ADMIN_SEED_PASSWORD not configured — using the documented development constant for the"
                    + " break-glass admin (never valid in production)");
        }

        if (!userDetailsManager.userExists(ADMIN_USERNAME)) {
            try {
                userDetailsManager.createUser(derivedUser(rawPassword));
                log.info("Bootstrapped break-glass admin user from environment configuration");
            } catch (DataIntegrityViolationException ex) {
                // Concurrent-replica bootstrap: the loser of the unique-username
                // insert converges onto the winner's row instead of failing
                // startup (the client initializer's CodeRabbit #241 pattern).
                if (!userDetailsManager.userExists(ADMIN_USERNAME)) {
                    // Not a duplicate-key failure after all — do not mask it.
                    throw ex;
                }
                convergeExisting(rawPassword);
                log.info("Converged break-glass admin user after concurrent bootstrap by another replica");
            }
            return;
        }
        convergeExisting(rawPassword);
    }

    private void convergeExisting(String rawPassword) {
        UserDetails existing = userDetailsManager.loadUserByUsername(ADMIN_USERNAME);
        if (matchesDerivedDefinition(existing, rawPassword)) {
            return;
        }
        userDetailsManager.updateUser(derivedUser(rawPassword));
        log.info("Converged break-glass admin user from environment configuration (password rotated or"
                + " definition drifted)");
    }

    private boolean matchesDerivedDefinition(UserDetails existing, String rawPassword) {
        if (!existing.isEnabled()) {
            return false;
        }
        if (!passwordEncoder.matches(rawPassword, existing.getPassword())) {
            return false;
        }
        return existing.getAuthorities().stream()
                .map(Object::toString)
                .allMatch("ROLE_ADMIN"::equals)
                && existing.getAuthorities().size() == 1;
    }

    private UserDetails derivedUser(String rawPassword) {
        return User.withUsername(ADMIN_USERNAME)
                .password(passwordEncoder.encode(rawPassword))
                .roles("ADMIN")
                .build();
    }
}
