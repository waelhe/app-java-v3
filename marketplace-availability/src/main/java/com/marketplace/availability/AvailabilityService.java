package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AvailabilityService implements AvailabilityPort {
    private final AvailabilityRuleRepository ruleRepository;
    private final ProviderTimeOffRepository timeOffRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderLookupPort providerLookupPort;

    public AvailabilityService(AvailabilityRuleRepository ruleRepository, ProviderTimeOffRepository timeOffRepository,
                               CurrentUserProvider currentUserProvider, ProviderLookupPort providerLookupPort) {
        this.ruleRepository = ruleRepository;
        this.timeOffRepository = timeOffRepository;
        this.currentUserProvider = currentUserProvider;
        this.providerLookupPort = providerLookupPort;
    }

    public ProviderAvailabilityRule addRule(UUID providerId, Integer dayOfWeek, java.time.LocalTime startsAt, java.time.LocalTime endsAt, Integer slotMinutes, Authentication authentication) {
        verifyProviderOwnerOrAdmin(providerId, authentication);
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("Availability rule start must be before end");
        }
        return ruleRepository.save(ProviderAvailabilityRule.create(providerId, dayOfWeek, startsAt, endsAt, slotMinutes));
    }

    public ProviderTimeOff addTimeOff(UUID providerId, Instant startsAt, Instant endsAt, String reason, Authentication authentication) {
        verifyProviderOwnerOrAdmin(providerId, authentication);
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("Time off start must be before end");
        }
        return timeOffRepository.save(ProviderTimeOff.create(providerId, startsAt, endsAt, reason));
    }

    @Transactional(readOnly = true)
    public List<ProviderAvailabilityRule> listRules(UUID providerId) { return ruleRepository.findByProviderId(providerId); }

    @Transactional(readOnly = true)
    public List<ProviderTimeOff> listTimeOff(UUID providerId) { return timeOffRepository.findByProviderId(providerId); }

    @Transactional(readOnly = true)
    public List<AvailabilitySlot> slots(UUID providerId, UUID listingId, LocalDate date) {
        int day = date.getDayOfWeek().getValue();
        return ruleRepository.findByProviderId(providerId).stream()
                .filter(rule -> rule.getDayOfWeek() == day)
                .map(rule -> new AvailabilitySlot(providerId, listingId,
                        date.atTime(rule.getStartsAt()).toInstant(ZoneOffset.UTC),
                        date.atTime(rule.getEndsAt()).toInstant(ZoneOffset.UTC), true))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public void requireAvailable(UUID providerId, UUID listingId, Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null) {
            return;
        }
        if (!timeOffRepository.findOverlapping(providerId, startsAt, endsAt).isEmpty()) {
            throw new IllegalStateException("Provider is not available for the requested time");
        }
    }

    private void verifyProviderOwnerOrAdmin(UUID providerId, Authentication authentication) {
        if (currentUserProvider.isAdmin(authentication)) {
            return;
        }
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        UUID providerUserId = providerLookupPort.getProvider(providerId).userId();
        if (!currentUserId.equals(providerUserId)) {
            throw new AccessDeniedException("Providers can only manage their own availability");
        }
    }
}
