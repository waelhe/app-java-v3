package com.marketplace.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The commercial-pricing configuration (the Saleor fit-gap study's Phase
 * 3): the marketplace's DEFAULT tax jurisdiction — the country whose
 * active {@link TaxRate} computes the tax component when the order's own
 * jurisdiction is not yet known (the delivery-address jurisdiction arrives
 * with the checkout fulfillment selection; until then the marketplace's
 * configured jurisdiction is the honest explicit policy, never a guess
 * from a currency code — the study's «do not imply tax compliance from
 * currency or price fields»).
 */
@ConfigurationProperties(prefix = "marketplace.pricing.commercial")
public record CommercialPricingProperties(
        @DefaultValue("SA") String defaultTaxCountry
) {

    public CommercialPricingProperties {
        defaultTaxCountry = defaultTaxCountry == null || defaultTaxCountry.isBlank()
                ? "SA"
                : defaultTaxCountry.strip().toUpperCase(java.util.Locale.ROOT);
    }
}
