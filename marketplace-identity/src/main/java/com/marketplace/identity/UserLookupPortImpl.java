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

    /**
     * R7 (Wave 5 — the unified WebSocket identity): the subject-keyed
     * lookup, the same {@code findBySubject} resolution the REST surface's
     * {@code IdentityUserProvider} rides — the users table's unique stable
     * key a minted token's {@code sub} carries. See
     * {@link UserLookupPort#findBySubject(String)} for the contract.
     */
    @Override
    public Optional<UserSummary> findBySubject(String subject) {
        return userRepository.findBySubject(subject)
                .map(u -> new UserSummary(u.getId(), u.getEmail(), u.getDisplayName(),
                        u.getRole().name(), u.getCreatedAt(), u.getUpdatedAt()));
    }
}
