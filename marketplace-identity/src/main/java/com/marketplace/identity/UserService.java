package com.marketplace.identity;

import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.shared.api.OAuth2UserProvisioningPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserSummary;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class UserService implements IdentitySpi, OAuth2UserProvisioningPort {

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final AuthAuditService auditService;

    public UserService(UserRepository userRepository,
                        UserDetailsManager userDetailsManager,
                        AuthAuditService auditService) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.auditService = auditService;
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

    @CacheEvict(cacheNames = {"users", "userSubjects"}, allEntries = true)
    public User syncFromOidc(JwtAuthenticationToken token) {
        String subject = token.getToken().getSubject();
        String email = token.getToken().getClaimAsString("email");
        String name = token.getToken().getClaimAsString("name");

        return userRepository.findBySubject(subject)
                .map(existing -> {
                    existing.updateProfile(email, name);
                    return existing;
                })
                .orElseGet(() -> {
                    UserRole role = resolveRole(token);
                    User user = User.create(subject, email, name, role);
                    return userRepository.save(user);
                });
    }

    @CacheEvict(cacheNames = {"users", "userSubjects"}, allEntries = true)
    public User updateProfile(UUID userId, String email, String displayName) {
        User user = getById(userId);
        user.updateProfile(email, displayName);
        return user;
    }

    @Override
    @CacheEvict(cacheNames = {"users", "userSubjects"}, allEntries = true)
    public UUID provisionUser(String provider, String providerId, String email, String displayName) {
        String subject = provider + ":" + providerId;
        User user = userRepository.findBySubject(subject)
                .map(existing -> {
                    existing.updateProfile(email, displayName);
                    return existing;
                })
                .orElseGet(() -> {
                    User newUser = User.create(subject, email, displayName, UserRole.CONSUMER);
                    return userRepository.save(newUser);
                });
        return user.getId();
    }

    private UserRole resolveRole(JwtAuthenticationToken token) {
        var roles = token.getToken().getClaimAsStringList("roles");
        if (roles != null && roles.contains("ADMIN")) return UserRole.ADMIN;
        if (roles != null && roles.contains("PROVIDER")) return UserRole.PROVIDER;
        return UserRole.CONSUMER;
    }

    @CacheEvict(cacheNames = {"users", "userSubjects"}, allEntries = true)
    public void updateUserRole(UUID userId, String newRole) {
        User user = getById(userId);
        UserRole role;
        try {
            role = UserRole.valueOf(newRole);
        } catch (IllegalArgumentException e) {
            throw new com.marketplace.shared.api.BadRequestException("Invalid role: " + newRole);
        }
        user.changeRole(role);

        // Update Spring Security authorities
        UserDetails userDetails = userDetailsManager.loadUserByUsername(user.getEmail());
        org.springframework.security.core.userdetails.UserDetails updatedUser =
                org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                        .password(userDetails.getPassword())
                        .roles(newRole)
                        .disabled(!userDetails.isEnabled())
                        .build();
        userDetailsManager.updateUser(updatedUser);

        auditService.log(user.getEmail(), AuthEventType.ROLE_CHANGED, "Role changed to " + newRole);
    }

    /**
     * Suspends a user account — disables login.
     */
    @Override
    @CacheEvict(cacheNames = {"users", "userSubjects"}, allEntries = true)
    public void suspendUser(UUID userId) {
        User user = getById(userId);
        UserDetails userDetails = userDetailsManager.loadUserByUsername(user.getEmail());
        org.springframework.security.core.userdetails.UserDetails updatedUser =
                org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                        .password(userDetails.getPassword())
                        .roles(user.getRole().name().replace("ROLE_", ""))
                        .disabled(true)
                        .build();
        userDetailsManager.updateUser(updatedUser);
        auditService.log(user.getEmail(), AuthEventType.ACCOUNT_DISABLED, "Account suspended by admin");
    }

    /**
     * Reactivates a suspended user account.
     */
    @Override
    @CacheEvict(cacheNames = {"users", "userSubjects"}, allEntries = true)
    public void reactivateUser(UUID userId) {
        User user = getById(userId);
        UserDetails userDetails = userDetailsManager.loadUserByUsername(user.getEmail());
        org.springframework.security.core.userdetails.UserDetails updatedUser =
                org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                        .password(userDetails.getPassword())
                        .roles(user.getRole().name().replace("ROLE_", ""))
                        .disabled(false)
                        .build();
        userDetailsManager.updateUser(updatedUser);
        auditService.log(user.getEmail(), AuthEventType.ACCOUNT_ENABLED, "Account reactivated by admin");
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
