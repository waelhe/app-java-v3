package com.marketplace.app.graphql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Hand-owned input record mapped by DGS CodeGen. Monetary value stays an exact
 * non-negative decimal string at the GraphQL wire boundary because GraphQL Int
 * is only 32-bit and Float is not exact for money.
 */
public record StoreProductCreateInput(
        @NotBlank @Size(max = 50) String storeCategoryCode,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 19) @Pattern(regexp = "^(0|[1-9][0-9]*)$") String priceMinor,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        UUID productTypeId) { }
