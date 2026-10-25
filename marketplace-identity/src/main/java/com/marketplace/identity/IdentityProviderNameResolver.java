package com.marketplace.identity;

import com.marketplace.shared.api.ProviderNameResolver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Identity-module implementation of {@link ProviderNameResolver}.
 * Resolves provider display names with a hierarchical fallback:
 * displayName → email → "Provider".
 * Uses batch resolution via {@code findAllById} to avoid N+1 queries.
 */
@Component
public class IdentityProviderNameResolver implements ProviderNameResolver {

    private final UserRepository userRepository;

    public IdentityProviderNameResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Map<UUID, String> resolveNames(Set<UUID> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(providerIds).stream()
                .collect(Collectors.toMap(
                        User::getId,
                        this::resolveDisplayName,
                        (left, right) -> left
                ));
    }

    private String resolveDisplayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        // A blank display name falls back to the NEUTRAL label — never the
        // account email (CodeRabbit agent findings, row 2 / CWE-200): this
        // projection feeds the PUBLIC providerName of catalog listing
        // summaries, and a private login address is not publishable provider
        // identity. The email leg is removed, not masked: every reader of
        // the public summary sees the same neutral label.
        return "Provider";
    }
}
