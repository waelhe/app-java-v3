package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L50 — the market item entity's factory contract (the
 * NeighborhoodEventTest shape): every authored column rides the
 * factory, the pricing pair carries the ONE rule's honest shape (a
 * gift carries no price at all; a sale carries cents + ISO currency),
 * a fresh item is never SOLD (the two-state vocabulary's own floor),
 * and fresh instances are independent (no shared mutable state).
 */
class NeighborhoodMarketItemTest {

    private final UUID authorId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();

    @Test
    void marketItemFactory_carriesEveryAuthoredColumnAndStatusStaysActive() {
        NeighborhoodMarketItem item = NeighborhoodMarketItem.marketItem(authorId, locationId,
                MarketCategory.FURNITURE, "أريكة جلسة عائلية 7 مقاعد — قماش قابل للغسل",
                MarketCondition.GOOD, 48000, "SAR", "شارع المسجد - مربع 2");

        assertThat(item.getId()).isNotNull();
        assertThat(item.getAuthorId()).isEqualTo(authorId);
        assertThat(item.getLocationId()).isEqualTo(locationId);
        assertThat(item.getCategory()).isEqualTo(MarketCategory.FURNITURE);
        assertThat(item.getTitle()).isEqualTo("أريكة جلسة عائلية 7 مقاعد — قماش قابل للغسل");
        assertThat(item.getCondition()).isEqualTo(MarketCondition.GOOD);
        assertThat(item.getPriceCents()).isEqualTo(48000);
        assertThat(item.getPriceCurrency()).isEqualTo("SAR");
        assertThat(item.getStatus()).isEqualTo(MarketItemStatus.ACTIVE);
        assertThat(item.getLocationLabel()).isEqualTo("شارع المسجد - مربع 2");
    }

    @Test
    void marketItemFactory_freeGiftCarriesNoPriceAtAll() {
        NeighborhoodMarketItem gift = NeighborhoodMarketItem.marketItem(authorId, locationId,
                MarketCategory.FREE, "مكتب دراسي خشبي بحالة ممتازة — إهداء لأسرة طلاب",
                MarketCondition.LIKE_NEW, null, null, "شارع المسجد - مربع 2");

        assertThat(gift.getCategory()).isEqualTo(MarketCategory.FREE);
        assertThat(gift.getPriceCents()).isNull();
        assertThat(gift.getPriceCurrency()).isNull();
        assertThat(gift.getStatus()).isEqualTo(MarketItemStatus.ACTIVE);
    }

    @Test
    void marketItemFactory_freshInstancesAreIndependent() {
        NeighborhoodMarketItem first = NeighborhoodMarketItem.marketItem(authorId, locationId,
                MarketCategory.FREE, "First", MarketCondition.LIKE_NEW, null, null, "Spot");
        NeighborhoodMarketItem second = NeighborhoodMarketItem.marketItem(authorId, locationId,
                MarketCategory.FREE, "Second", MarketCondition.LIKE_NEW, null, null, "Spot");

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getTitle()).isEqualTo("First");
        assertThat(second.getTitle()).isEqualTo("Second");
    }

    @Test
    void marketVocabulary_isTheProductsOwnFiveCategoriesAndTwoConditionsAndTwoStates() {
        // D-N7's two-sided discipline, pinned: the enum memberships are
        // the frontend contract's own (MARKET_CATEGORIES and the
        // condition chips measured verbatim).
        assertThat(MarketCategory.values()).containsExactly(
                MarketCategory.FREE, MarketCategory.FURNITURE, MarketCategory.ELECTRONICS,
                MarketCategory.TOOLS, MarketCategory.OTHER);
        assertThat(MarketCondition.values()).containsExactly(
                MarketCondition.LIKE_NEW, MarketCondition.GOOD);
        assertThat(MarketItemStatus.values()).containsExactly(
                MarketItemStatus.ACTIVE, MarketItemStatus.SOLD);
    }
}
