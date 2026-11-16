package com.marketplace.app.graphql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Exact-money and display-field input for provider-owned product edits. */
public record StoreProductUpdateInput(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 19) @Pattern(regexp = "^(0|[1-9][0-9]*)$") String priceMinor) { }
