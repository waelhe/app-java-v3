package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductAdminSpi;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductListFilterTest {

    @Test
    void blankSearchAndCategoryNormalizeToAbsentPredicates() {
        var filter = new ProductAdminSpi.ProductListFilter(
                "  ", "  ", null, null, null, null);

        assertThat(filter.search()).isNull();
        assertThat(filter.storeCategoryCode()).isNull();
    }

    @Test
    void trimsSearchAndCategoryWithoutChangingTypedIdentifiers() {
        UUID productTypeId = UUID.randomUUID();
        var filter = new ProductAdminSpi.ProductListFilter(
                "  Stainless steel  ", "  home-appliances ", productTypeId,
                "ACTIVE", 100L, 900L);

        assertThat(filter.search()).isEqualTo("Stainless steel");
        assertThat(filter.storeCategoryCode()).isEqualTo("home-appliances");
        assertThat(filter.productTypeId()).isEqualTo(productTypeId);
        assertThat(filter.status()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsRangeWithMinimumAboveMaximum() {
        assertThatThrownBy(() -> new ProductAdminSpi.ProductListFilter(
                null, null, null, null, 101L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minPriceMinor must not exceed maxPriceMinor");
    }

    @Test
    void rejectsNegativePriceBounds() {
        assertThatThrownBy(() -> new ProductAdminSpi.ProductListFilter(
                null, null, null, null, -1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price bounds must be non-negative");
    }

    @Test
    void rejectsUnrecognisedLifecycleStatus() {
        assertThatThrownBy(() -> new ProductAdminSpi.ProductListFilter(
                null, null, null, "PUBLISHED", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported product lifecycle");
    }

    @Test
    void sortVocabularyIsClosedAndHasDeterministicDefaults() {
        var sort = ProductAdminSpi.ProductListSort.defaultSort();

        assertThat(sort.field()).isEqualTo(ProductAdminSpi.ProductListSortField.CREATED_AT);
        assertThat(sort.direction()).isEqualTo(ProductAdminSpi.ProductListSortDirection.DESC);
        assertThat(sort.field().property()).isEqualTo("createdAt");
    }
}
