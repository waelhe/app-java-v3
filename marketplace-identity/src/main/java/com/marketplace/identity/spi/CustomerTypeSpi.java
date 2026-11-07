package com.marketplace.identity.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * additive customer-type seam of the {@code identity-spi} named interface
 * — the B2B segmentation registry with its assigned (catalog-registered)
 * attributes behind the {@code customerType*} mutations and the
 * {@code customerType(s)} read roots.
 */
@NamedInterface("identity-spi")
public interface CustomerTypeSpi {

    /** One page of the registry (the forward-only keyset contract). */
    record CustomerTypePage(Window<CustomerTypeView> window, long total) {
    }

    /** The executed sorts (the owning-column + id-tiebreaker subset). */
    record CustomerTypeSort(Field field, boolean ascending) {

        public enum Field { NAME, SLUG }
    }

    CustomerTypeView create(Authentication caller, String name, String slug, Boolean isDefault);

    CustomerTypeView update(Authentication caller, UUID id, String name, String slug,
                            Boolean isDefault);

    CustomerTypeView delete(Authentication caller, UUID id);

    CustomerTypeView assignAttributes(Authentication caller, UUID customerTypeId,
                                      List<UUID> attributeIds);

    CustomerTypeView unassignAttributes(Authentication caller, UUID customerTypeId,
                                        List<UUID> attributeIds);

    CustomerTypeView reorderAttributes(Authentication caller, UUID customerTypeId,
                                       List<UUID> attributeIds, List<Integer> deltas);

    Optional<CustomerTypeView> find(Authentication caller, UUID id);

    CustomerTypePage scroll(Authentication caller, CustomerTypeSort sort,
                            KeysetScrollPosition position, Limit limit, String search);
}
