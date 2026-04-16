package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public User getBySubject(String subject) {
        return userRepository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + subject));
    }

    /**
     * Syncs user from OIDC token — creates if new, updates if changed.
     */
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

    private UserRole resolveRole(JwtAuthenticationToken token) {
        var roles = token.getToken().getClaimAsStringList("roles");
        if (roles != null && roles.contains("ADMIN")) return UserRole.ADMIN;
        if (roles != null && roles.contains("PROVIDER")) return UserRole.PROVIDER;
        return UserRole.CONSUMER;
    }
}