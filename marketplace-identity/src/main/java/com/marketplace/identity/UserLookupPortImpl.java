package com.marketplace.identity;

import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class UserLookupPortImpl implements UserLookupPort {

    private final UserRepository userRepository;

    public UserLookupPortImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<UserSummary> findById(UUID userId) {
        return userRepository.findById(userId).map(UserLookupPortImpl::toSummary);
    }

    /**
     * W1 (§4.4/§4.5): the batch form — one {@code findAllById} for a whole
     * reviews page's distinct authors (the ProviderNameResolver rationale
     * "batch resolution via findAllById to avoid N+1 queries"). Ids without
     * a row are simply absent (the caller's own fallback applies).
     */
    @Override
    public Map<UUID, UserSummary> findAllByIds(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(
                        User::getId,
                        UserLookupPortImpl::toSummary,
                        (left, right) -> left));
    }

    /**
     * I7 note: the RAW profile values plus the pseudonymization marker —
     * the neutral "former member" rendering is the reader's decision
     * ({@code UserSummary.publicDisplayName()}), so every cross-module
     * surface answers the same word for the same account.
     */
    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getDisplayName(),
                user.getRole().name(), user.getCreatedAt(), user.getUpdatedAt(),
                user.getPseudonymizedAt());
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

