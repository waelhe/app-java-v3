package com.marketplace.identity;

import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserSummary;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Transactional
public class UserService implements IdentitySpi {

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final Set<String> USER_CACHE_NAMES = Set.of("users", "userSubjects");

    public UserService(UserRepository userRepository,
                       ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
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
     */
    public User syncFromOidc(JwtAuthenticationToken token) {
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

    public void updateUserRole(UUID userId, String newRole) {
        User user = getById(userId);
        user.changeRole(UserRole.valueOf(newRole));
        eventPublisher.publishEvent(new CacheInvalidationRequested(USER_CACHE_NAMES));
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
