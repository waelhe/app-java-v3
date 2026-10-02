package com.marketplace.provider.spi;

import com.marketplace.provider.ProviderProfile;
import com.marketplace.provider.ProviderRepository;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Transactional(readOnly = true)
public class ProviderLookupAdapter implements ProviderLookupPort {

    private final ProviderRepository providerRepository;

    public ProviderLookupAdapter(ProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    @Override
    public Optional<ProviderSummary> findById(UUID providerId) {
        return providerRepository.findById(providerId)
                .map(ProviderLookupAdapter::toSummary);
    }

    @Override
    public Optional<ProviderSummary> findByUserId(UUID userId) {
        return providerRepository.findByUserId(userId)
                .map(ProviderLookupAdapter::toSummary);
    }

    /**
     * W4 (yelp-level plan §5 — G21): the batch form — one
     * {@code findByUserIdIn} for a whole "my follows" page (the
     * {@code UserLookupPortImpl.findAllByIds} W1 precedent verbatim). Ids
     * with no provider row are simply absent (the caller's own fallback
     * applies); duplicate keys collapse to the first row (the caller's
     * page already deduplicated by construction — one follow row per
     * provider).
     */
    @Override
    public Map<UUID, ProviderSummary> findAllByUserIds(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return providerRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(
                        ProviderProfile::getUserId,
                        ProviderLookupAdapter::toSummary,
                        (left, right) -> left));
    }

    private static ProviderSummary toSummary(ProviderProfile profile) {
        return new ProviderSummary(profile.getId(), profile.getDisplayName(),
                profile.getStatus().name(), profile.getUserId());
    }
}
