package com.marketplace.provider;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): the declared working-hours
 * repository. The module's own house shape (JpaRepository +
 * JpaSpecificationExecutor + Envers RevisionRepository — the
 * ProviderRepository form).
 */
public interface BusinessHourRepository extends JpaRepository<BusinessHour, UUID>,
        JpaSpecificationExecutor<BusinessHour>,
        RevisionRepository<BusinessHour, UUID, Integer> {

    /**
     * The page's own read: one provider's declared week in day order —
     * the deterministic total order the sitemap/list conventions apply
     * (D-N5); soft-deleted rows are structurally filtered by
     * {@code @SoftDelete}.
     */
    List<BusinessHour> findByProviderIdOrderByDayOfWeekAsc(UUID providerId);

    /**
     * The self-service seam: the live row for one provider-day pair —
     * the unique key's read form ({@code uq_business_hours_provider_day}).
     */
    Optional<BusinessHour> findByProviderIdAndDayOfWeek(UUID providerId, int dayOfWeek);

    /** The write surface's takeover check: whether the provider declares anything at all. */
    boolean existsByProviderId(UUID providerId);
}
