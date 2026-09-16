package com.marketplace.provider;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L40 (realestate systems plan §5 — view analytics): the views window type
 * gate — the whitelist is the plan's own list ("نوافذ 7/30/90 يومًا"), the
 * default is the house stats default (30), and the day arithmetic keeps
 * TODAY inside every window (the still-accumulating bucket an analytics
 * surface must not hide). The L27 lesson: the type rejects before any
 * query runs.
 */
class ListingViewsWindowTest {

    @Test
    void whitelist_acceptsExactlyTheThreeDocumentedWindows() {
        assertThatCode(() -> new ListingViewsWindow(7)).doesNotThrowAnyException();
        assertThatCode(() -> new ListingViewsWindow(30)).doesNotThrowAnyException();
        assertThatCode(() -> new ListingViewsWindow(90)).doesNotThrowAnyException();
    }

    @Test
    void anythingElse_isRejectedAtConstruction() {
        for (int bad : new int[]{1, 6, 8, 31, 89, 91, 365, 0, -7}) {
            assertThatThrownBy(() -> new ListingViewsWindow(bad))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("7, 30 or 90");
        }
    }

    @Test
    void omittedParameter_defaultsToThirtyDays() {
        assertThat(ListingViewsWindow.ofDays(null).days()).isEqualTo(30);
        assertThat(ListingViewsWindow.ofDays(7).days()).isEqualTo(7);
    }

    @Test
    void sinceInclusive_coversTheWholeWindowIncludingToday() {
        LocalDate today = LocalDate.of(2026, 9, 16);

        // 7 days = today-6 .. today (today itself included)
        assertThat(new ListingViewsWindow(7).sinceInclusive(today))
                .isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(new ListingViewsWindow(30).sinceInclusive(today))
                .isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(new ListingViewsWindow(90).sinceInclusive(today))
                .isEqualTo(LocalDate.of(2026, 6, 19));
    }
}
