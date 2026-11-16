package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shared catalog-to-checkout pricing contract independently of
 * whichever module consumes it. The storefront lifecycle is intentionally
 * closed: only an ACTIVE product with an ACTIVE variant is purchasable.
 */
class ProductPricingPortTest {

    @Test
    void variantIsPurchasableOnlyWhenBothProductAndVariantAreActive() {
        UUID variantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID providerId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        for (ProductPricingPort.StorefrontState productState
                : ProductPricingPort.StorefrontState.values()) {
            for (ProductPricingPort.StorefrontState variantState
                    : ProductPricingPort.StorefrontState.values()) {
                ProductPricingPort.VariantPrice price = new ProductPricingPort.VariantPrice(
                        variantId, productId, providerId, 1250L, "SAR", productState, variantState);

                assertThat(price.purchasable())
                        .as("product=%s, variant=%s", productState, variantState)
                        .isEqualTo(productState == ProductPricingPort.StorefrontState.ACTIVE
                                && variantState == ProductPricingPort.StorefrontState.ACTIVE);
            }
        }
    }
}
