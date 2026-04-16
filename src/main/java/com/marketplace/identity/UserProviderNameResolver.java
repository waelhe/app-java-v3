package com.marketplace.identity;

import com.marketplace.shared.api.ProviderNameResolver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class UserProviderNameResolver implements ProviderNameResolver {

    private static final String UNKNOWN_PROVIDER_NAME = "Unknown Provider";
    private final UserRepository userRepository;

    public UserProviderNameResolver(UserRepository userRepository) {
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
                        user -> user.getDisplayName() != null && !user.getDisplayName().isBlank()
                                ? user.getDisplayName()
                                : UNKNOWN_PROVIDER_NAME
                ));
    }
}
