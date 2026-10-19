package com.marketplace.app.graphql;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Variant registration command; provider and currency are always resolved from the owning product. */
public record StoreProductVariantCreateInput(
        @NotBlank @Size(max = 100) String sku,
        @Size(max = 200) String name,
        @NotBlank @Size(max = 19) @Pattern(regexp = "^(0|[1-9][0-9]*)$") String priceMinor,
        @Valid List<StoreProductAttributeValueInput> attributeValues) {
    public StoreProductVariantCreateInput {
        attributeValues = attributeValues == null ? List.of() : List.copyOf(attributeValues);
    }
}
