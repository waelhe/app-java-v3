package com.marketplace.app.graphql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ServiceInput(
    @NotBlank String name,
    String description,
    @NotBlank String category,
    @NotNull @Positive Long priceCents
) {}
