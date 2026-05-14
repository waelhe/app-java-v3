package com.marketplace.identity;

import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class IdentityUserProvider implements CurrentUserProvider {

    private final UserRepository userRepository;

    public IdentityUserProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UUID getCurrentUserId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            String subject = jwtToken.getToken().getSubject();
            return userRepository.findBySubject(subject)
                    .map(User::getId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found for subject: " + subject));
        }
        throw new IllegalArgumentException("Unsupported authentication type");
    }

    @Override
    public boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
    }
}
