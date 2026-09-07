package com.marketplace.identity;

import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserSummary;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Transactional
public class UserService implements IdentitySpi {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final UserDetailsManager userDetailsManager;
    private final JdbcTemplate jdbcTemplate;

    private static final Set<String> USER_CACHE_NAMES = Set.of("users", "userSubjects");

    /** Authorization rows in the SAS store (V13) are keyed by the principal name. */
    static final String DELETE_AUTHORIZATIONS_BY_PRINCIPAL =
            "DELETE FROM oauth2_authorization WHERE principal_name = ?";

    /**
     * Counting constraint (roadmap L23 acceptance 2): the last active ADMIN is
     * untouchable. The lock matters (CodeRabbit round 1): the counting read
     * and the flip below must be atomic — two concurrent disable requests
     * must not both pass a count of 2 and leave the system with zero active
     * admins. {@code FOR UPDATE} on the counted rows (stable order, no
     * deadlock) holds them for the rest of this transaction, so a concurrent
     * transaction re-reads the post-commit state. Row locks on an aggregate
     * are not allowed on PostgreSQL, hence the row select counted in Java.
     */
    static final String LOCK_ACTIVE_ADMINS = """
            SELECT u.username FROM auth_users u
             WHERE u.enabled = true
               AND EXISTS (SELECT 1 FROM auth_authorities a
                            WHERE a.username = u.username AND a.authority = 'ROLE_ADMIN')
             ORDER BY u.username
             FOR UPDATE
            """;

    public UserService(UserRepository userRepository,
                       ApplicationEventPublisher eventPublisher,
                       UserDetailsManager userDetailsManager,
                       JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.userDetailsManager = userDetailsManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    @Cacheable("users")
    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    @Cacheable("userSubjects")
    public User getBySubject(String subject) {
        return userRepository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + subject));
    }

    @Transactional(readOnly = true)
    public Page<User> findAll(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<UserSummary> findAllSummaries(Pageable pageable) {
        return findAll(pageable).map(this::toUserSummary);
    }

    /**
     * Syncs user from OIDC token — creates if new, updates if changed.
     * Publishes a cache invalidation only when the user was created or
     * the profile actually changed, avoiding redundant cache thrash and
     * unbounded growth of the event publication archive on every /me call.
     *
     * <p><b>Defect fix shipped with L23:</b> the parameter is the plain
     * {@link Authentication} (house convention — every other controller
     * receives it) narrowed by an {@code instanceof} guard, the
     * {@code IdentityUserProvider} pattern verbatim. The previous
     * {@code @AuthenticationPrincipal JwtAuthenticationToken} parameter
     * resolved to {@code null} for real Bearer requests —
     * {@code AuthenticationPrincipalArgumentResolver} resolves to
     * {@code authentication.getPrincipal()} (the {@code Jwt} for the
     * resource server), the type mismatch yields {@code null}, and every
     * real-client /me call died with 500 INT-001. No prior test injected a
     * real JWT through the resolver (the slice mocked the service), so the
     * defect sat latent on main until the L23 gate test exercised /me
     * end-to-end.
     */
    @Observed(name = "user.sync.oidc")
    public User syncFromOidc(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalArgumentException(
                    "Unsupported authentication type: " + authentication);
        }
        String subject = token.getToken().getSubject();
        String email = token.getToken().getClaimAsString("email");
        String name = token.getToken().getClaimAsString("name");

        AtomicBoolean profileChanged = new AtomicBoolean(false);
        User user = userRepository.findBySubject(subject)
                .map(existing -> {
                    if (existing.updateProfile(email, name)) {
                        profileChanged.set(true);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    UserRole role = resolveRole(token);
                    User newUser = User.create(subject, email, name, role);
                    profileChanged.set(true);
                    return userRepository.save(newUser);
                });
        if (profileChanged.get()) {
            eventPublisher.publishEvent(new CacheInvalidationRequested(USER_CACHE_NAMES));
        }
        return user;
    }

    private UserRole resolveRole(JwtAuthenticationToken token) {
        var roles = token.getToken().getClaimAsStringList("roles");
        if (roles != null && roles.contains("ADMIN")) return UserRole.ADMIN;
        if (roles != null && roles.contains("PROVIDER")) return UserRole.PROVIDER;
        return UserRole.CONSUMER;
    }

    @Observed(name = "user.role.update")
    public void updateUserRole(UUID userId, String newRole) {
        User user = getById(userId);
        user.changeRole(UserRole.valueOf(newRole));
        eventPublisher.publishEvent(new CacheInvalidationRequested(USER_CACHE_NAMES));
    }

    /**
     * L23 (feature-expansion roadmap §5) — administrative account disable/enable.
     *
     * <p><b>The operative change</b> is {@code auth_users.enabled}, written through
     * the framework-managed {@link UserDetailsManager} bean (the same manager the
     * authentication chain reads from — {@code JdbcUserDetailsManager} wired in
     * {@code SecurityConfig}: a disabled row makes the next form-login attempt
     * throw {@code DisabledException}, so no new authorization code or token can
     * be minted for the account).
     *
     * <p><b>Tokens already issued:</b> Spring Authorization Server 7.1.1 does not
     * re-read {@code enabled} on the {@code refresh_token} grant (it deserializes
     * the principal stored in {@code oauth2_authorization} — verified against the
     * framework source). Disabling therefore also removes the account's
     * authorization rows — the same store OAuth2 token revocation operates on —
     * so the next refresh-token request returns {@code invalid_grant}
     * ("Invalid request: refresh_token is invalid"). The already-minted access
     * JWTs are self-contained and die by their 900s TTL; the roadmap's basis is
     * the login chain, so per-request JWT revocation is deliberately out of
     * scope. Enabling emits no token: the user simply logs in again.
     *
     * <p><b>The audit record:</b> the action with its reason lands as a
     * structured log line (actor, target, old→new status, reason — the same
     * record convention the payments module uses for money movements). The
     * {@code audit_log} table (V8) was assessed as the target and rejected with
     * live CI evidence: its script is not idempotent, which breaks the
     * login-gate {@code sql.init} pattern the CI "Build &amp; Test" job runs on
     * a database the OpenAPI gate has already migrated with Flyway
     * ({@code relation "idx_audit_entity" already exists}), and a first wiring
     * of a 31-migration-old unused table exceeds the roadmap's effort=1 scope.
     *
     * <p><b>Counting constraint</b> (acceptance 2): disabling the last active
     * ADMIN is rejected — the guard counts enabled users holding
     * {@code ROLE_ADMIN} in {@code auth_authorities} (the login-side authority
     * that the JWT {@code roles} claim is minted from).
     */
    @Observed(name = "user.status.update")
    @Override
    public void updateUserStatus(UUID userId, String status, String reason, String actor) {
        boolean disable = "DISABLED".equals(status);
        if (!disable && !"ENABLED".equals(status)) {
            throw new IllegalArgumentException(
                    "Unknown account status: " + status + " (expected DISABLED or ENABLED)");
        }
        User user = getById(userId);
        String username = user.getSubject();
        UserDetails stored;
        try {
            stored = userDetailsManager.loadUserByUsername(username);
        } catch (UsernameNotFoundException ex) {
            throw new ResourceNotFoundException(
                    "No authentication account for user: " + userId + " (subject: " + username + ")");
        }

        if (disable && stored.isEnabled() && hasAdminAuthority(stored)) {
            // The lock is taken BEFORE the guard's decision (see LOCK_ACTIVE_ADMINS):
            // the counted rows stay locked through the flip, so concurrent
            // disables serialize on the same set instead of racing the count.
            List<String> activeAdmins = jdbcTemplate.queryForList(LOCK_ACTIVE_ADMINS, String.class);
            if (activeAdmins.size() <= 1) {
                throw new ConflictException("Cannot disable the last active ADMIN account");
            }
        }

        boolean wasEnabled = stored.isEnabled();
        // The builder's disabled(...) flag drives auth_users.enabled on the manager's
        // updateUser SQL; password and authorities are replayed verbatim so only the
        // flag flips. The remaining account flags are preserved from the loaded row.
        userDetailsManager.updateUser(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password(stored.getPassword())
                .authorities(stored.getAuthorities())
                .accountExpired(!stored.isAccountNonExpired())
                .accountLocked(!stored.isAccountNonLocked())
                .credentialsExpired(!stored.isCredentialsNonExpired())
                .disabled(disable)
                .build());

        if (disable) {
            jdbcTemplate.update(DELETE_AUTHORIZATIONS_BY_PRINCIPAL, username);
        }

        log.info("Account status audit: userId={}, username={}, status: {} -> {}, actor={}, reason='{}'",
                userId, username, wasEnabled ? "ENABLED" : "DISABLED",
                disable ? "DISABLED" : "ENABLED", actor, reason);
    }

    private static boolean hasAdminAuthority(UserDetails details) {
        return details.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private UserSummary toUserSummary(User user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().name(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
