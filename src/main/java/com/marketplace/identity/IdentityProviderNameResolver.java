package com.marketplace.identity;

import com.marketplace.shared.api.ProviderNameResolver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
                .collect(Collectors.toMap(User::getId, User::getDisplayName, (left, right) -> left));
    }
}
