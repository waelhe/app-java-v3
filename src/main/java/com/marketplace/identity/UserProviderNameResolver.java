package com.marketplace.identity;

import com.marketplace.catalog.ProviderNameResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UserProviderNameResolver implements ProviderNameResolver {

    private static final String UNKNOWN_PROVIDER_NAME = "Unknown Provider";
    private final UserRepository userRepository;

    public UserProviderNameResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public String resolveProviderName(UUID providerId) {
        return userRepository.findById(providerId)
                .map(User::getDisplayName)
                .filter(displayName -> displayName != null && !displayName.isBlank())
                .orElse(UNKNOWN_PROVIDER_NAME);
    }
}
