package com.marketplace.identity;

import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class UserLookupPortImpl implements UserLookupPort {

    private final UserRepository userRepository;

    public UserLookupPortImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<UserSummary> findById(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> new UserSummary(u.getId(), u.getEmail(), u.getDisplayName(),
                        u.getRole().name(), u.getCreatedAt(), u.getUpdatedAt()));
    }
}
