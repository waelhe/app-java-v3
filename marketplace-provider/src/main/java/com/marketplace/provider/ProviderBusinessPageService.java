package com.marketplace.provider;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): the provider-owned
 * business-page blocks' write surface — the declared working hours, the
 * declared services list and the declared service areas.
 *
 * <p><b>Why a separate service (the ProviderPublicPageService pattern):</b>
 * the write surface rides its own transactional boundaries and
 * authorization checks without inflating the cached read services — the
 * same structural answer the module applied to the stats and public-page
 * concerns.
 *
 * <p><b>Ownership (the module's own law — {@code ProviderService
 * .verifyOwnership}'s exact rule, admin bypass included):</b> every write
 * resolves the profile then checks the caller owns it; any mismatch is
 * {@link AccessDeniedException}. The verification SUBMISSION and its
 * administrative RESOLUTION stay in {@code ProviderService} with the
 * profile's other lifecycle transitions (the cached-entity invalidation
 * concern lives there).
 */
@Service
public class ProviderBusinessPageService {

    private final ProviderService providerService;
    private final CurrentUserProvider currentUserProvider;
    private final BusinessHourRepository businessHourRepository;
    private final OfferedServiceRepository offeredServiceRepository;
    private final ServiceAreaRepository serviceAreaRepository;

    public ProviderBusinessPageService(ProviderService providerService,
                                       CurrentUserProvider currentUserProvider,
                                       BusinessHourRepository businessHourRepository,
                                       OfferedServiceRepository offeredServiceRepository,
                                       ServiceAreaRepository serviceAreaRepository) {
        this.providerService = providerService;
        this.currentUserProvider = currentUserProvider;
        this.businessHourRepository = businessHourRepository;
        this.offeredServiceRepository = offeredServiceRepository;
        this.serviceAreaRepository = serviceAreaRepository;
    }

    // -- Working hours (G11) -------------------------------------------------

    /**
     * The declared week — PUT replacement semantics (the house's
     * documented PUT contract: the request's list IS the week). Each
     * entry upserts by its day: a day already declared moves its window,
     * a new day inserts, a day absent from the request is soft-deleted.
     * The unique key (provider×day, V88) holds throughout — at most one
     * live write per day, all inside one transaction.
     */
    @Observed(name = "provider.business-hours.replace")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public List<BusinessHour> replaceHours(UUID providerId, List<HoursEntry> entries,
                                           Authentication authentication) {
        ProviderProfile provider = owned(providerId, authentication);
        List<HoursEntry> declared = entries == null ? List.of() : entries;
        if (declared.size() > 7) {
            throw new IllegalArgumentException("at most 7 entries — one per weekday");
        }
        Set<Integer> declaredDays = new HashSet<>();
        for (HoursEntry entry : declared) {
            if (!declaredDays.add(entry.dayOfWeek().getValue())) {
                throw new IllegalArgumentException(
                        "duplicate weekday: " + entry.dayOfWeek());
            }
        }
        // Soft-delete every live row the request does not re-declare.
        for (BusinessHour hour : businessHourRepository
                .findByProviderIdOrderByDayOfWeekAsc(provider.getId())) {
            if (!declaredDays.contains(hour.getDayOfWeek().getValue())) {
                businessHourRepository.delete(hour);
            }
        }
        // Upsert the declared days.
        for (HoursEntry entry : declared) {
            businessHourRepository.findByProviderIdAndDayOfWeek(provider.getId(),
                            entry.dayOfWeek().getValue())
                    .ifPresentOrElse(existing -> {
                        existing.update(entry.dayOfWeek(), entry.opensAt(), entry.closesAt());
                        businessHourRepository.save(existing);
                    }, () -> businessHourRepository.save(BusinessHour.create(
                            provider.getId(), entry.dayOfWeek(), entry.opensAt(),
                            entry.closesAt())));
        }
        return businessHourRepository.findByProviderIdOrderByDayOfWeekAsc(provider.getId());
    }

    /** The public read: one provider's declared week in day order. */
    @Transactional(readOnly = true)
    public List<BusinessHour> getHours(UUID providerId) {
        return businessHourRepository.findByProviderIdOrderByDayOfWeekAsc(providerId);
    }

    // -- Services list (G12) -------------------------------------------------

    /**
     * Adds one declared service — position auto-allocated as
     * {@code max(live positions) + 1} (the W1 max-allocation lesson:
     * count-based allocation re-issues positions soft deletion still
     * holds; the max never goes backward).
     */
    @Observed(name = "provider.services.add")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public OfferedService addService(UUID providerId, ServiceEntry entry,
                                     Authentication authentication) {
        ProviderProfile provider = owned(providerId, authentication);
        if (entry == null) {
            throw new IllegalArgumentException("service entry is required");
        }
        int nextPosition = offeredServiceRepository.findMaxPositionByProviderId(provider.getId())
                .orElse(-1) + 1;
        return offeredServiceRepository.save(OfferedService.create(
                provider.getId(), entry.title(), entry.description(),
                entry.durationMinutes(), entry.priceCents(), entry.currency(), nextPosition));
    }

    /**
     * Updates one declared service's display fields (PUT replacement —
     * the entity's own documented contract; the position key is untouched
     * here, it moves only through {@link #moveService}).
     */
    @Observed(name = "provider.services.update")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public OfferedService updateService(UUID providerId, UUID serviceId, ServiceEntry entry,
                                        Authentication authentication) {
        ProviderProfile provider = owned(providerId, authentication);
        if (entry == null) {
            throw new IllegalArgumentException("service entry is required");
        }
        OfferedService service = ownedService(provider.getId(), serviceId);
        service.update(entry.title(), entry.description(), entry.durationMinutes(),
                entry.priceCents(), entry.currency());
        return offeredServiceRepository.save(service);
    }

    /**
     * Moves one declared service within the menu — the swap form: the
     * target position's occupant (if any) takes the mover's old position,
     * the mover takes the target. Two writes, one transaction, the unique
     * key never violated.
     */
    @Observed(name = "provider.services.move")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public List<OfferedService> moveService(UUID providerId, UUID serviceId, int newPosition,
                                            Authentication authentication) {
        ProviderProfile provider = owned(providerId, authentication);
        OfferedService mover = ownedService(provider.getId(), serviceId);
        if (newPosition < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        if (newPosition != mover.getPosition()) {
            offeredServiceRepository.findByProviderIdAndPosition(provider.getId(), newPosition)
                    .ifPresent(occupant -> {
                        occupant.moveTo(mover.getPosition());
                        offeredServiceRepository.save(occupant);
                    });
            mover.moveTo(newPosition);
            offeredServiceRepository.save(mover);
        }
        return offeredServiceRepository.findByProviderIdOrderByPositionAsc(provider.getId());
    }

    /** Soft-deletes one declared service (the BaseEntity convention). */
    @Observed(name = "provider.services.remove")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public void removeService(UUID providerId, UUID serviceId, Authentication authentication) {
        ProviderProfile provider = owned(providerId, authentication);
        offeredServiceRepository.delete(ownedService(provider.getId(), serviceId));
    }

    /** The public read: one provider's declared menu in position order. */
    @Transactional(readOnly = true)
    public List<OfferedService> getServices(UUID providerId) {
        return offeredServiceRepository.findByProviderIdOrderByPositionAsc(providerId);
    }

    // -- Service areas (G13) -------------------------------------------------

    /**
     * Declares one more served area — the geo-tree node id must be real
     * (the FK's own law) and not already declared (the unique key's read
     * form; the loud failure teaches the client the set's contract).
     */
    @Observed(name = "provider.service-areas.add")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public ServiceArea addArea(UUID providerId, UUID locationId, Authentication authentication) {
        ProviderProfile provider = owned(providerId, authentication);
        if (serviceAreaRepository.existsByProviderIdAndLocationId(provider.getId(), locationId)) {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "service area already declared: " + locationId);
        }
        return serviceAreaRepository.save(ServiceArea.create(provider.getId(), locationId));
    }

    /** Withdraws one declared area (soft delete — the set is the claim). */
    @Observed(name = "provider.service-areas.remove")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public void removeArea(UUID providerId, UUID areaId, Authentication authentication) {
        ProviderProfile provider = owned(providerId, authentication);
        ServiceArea area = serviceAreaRepository.findById(areaId)
                .filter(a -> a.getProviderId().equals(provider.getId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Service area not found for this provider: " + areaId));
        serviceAreaRepository.delete(area);
    }

    /** The public read: one provider's declared areas. */
    @Transactional(readOnly = true)
    public List<ServiceArea> getAreas(UUID providerId) {
        return serviceAreaRepository.findByProviderIdOrderByIdAsc(providerId);
    }

    // -- helpers --------------------------------------------------------------

    private ProviderProfile owned(UUID providerId, Authentication authentication) {
        ProviderProfile provider = providerService.getById(providerId);
        if (!currentUserProvider.isAdmin(authentication)
                && (provider.getUserId() == null
                    || !provider.getUserId().equals(
                            currentUserProvider.getCurrentUserId(authentication)))) {
            throw new AccessDeniedException("You do not own this provider");
        }
        return provider;
    }

    private OfferedService ownedService(UUID providerId, UUID serviceId) {
        return offeredServiceRepository.findById(serviceId)
                .filter(s -> s.getProviderId().equals(providerId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Service not found for this provider: " + serviceId));
    }

    // -- request shapes -------------------------------------------------------

    /** One declared window: the ISO weekday and the day's opening/closing times. */
    public record HoursEntry(DayOfWeek dayOfWeek, LocalTime opensAt, LocalTime closesAt) {
    }

    /** One declared service: title/description/duration/price — the entity's own validation applies. */
    public record ServiceEntry(String title, String description, Integer durationMinutes,
                               Long priceCents, String currency) {
    }
}
