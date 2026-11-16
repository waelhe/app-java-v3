package com.marketplace.identity.spi;

import org.springframework.data.domain.Window;

import java.util.List;

/**
 * P1 (the canonical Saleor plan §5-P1): one page of the staff-list
 * keyset scroll — the {@code Window} of account projections plus the
 * total count Saleor's {@code UserCountableConnection.totalCount}
 * carries (the whole-collection count: the first slice implements no
 * filter, so the filtered count and the collection count are the same
 * number — the registry records that equivalence's scope).
 *
 * @param window   one bounded page of accounts in the requested sort
 * @param total    the total number of accounts in the collection
 */
public record UserAccountPage(Window<UserAccountView> window, long total) {

    /** The page's elements in scroll order. */
    public List<UserAccountView> content() {
        return window.getContent();
    }
}
