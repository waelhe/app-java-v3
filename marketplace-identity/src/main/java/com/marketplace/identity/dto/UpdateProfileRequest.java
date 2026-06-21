package com.marketplace.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for updating the user's profile.
 *
 * <p>Email is NOT included -- it is immutable via this endpoint (see
 * {@link com.marketplace.identity.UserService#updateProfile}). Email changes
 * require a separate verified email-change flow because {@code auth_users.username}
 * is keyed to the email and cannot be renamed via {@code UserDetailsManager.updateUser}.
 *
 * <p>Previously this DTO had {@code @NotBlank @Email String email}, but the service
 * rejected any email different from the current one -- clients were forced to echo
 * the current email, which they may not know. Removing the field eliminates the
 * contradiction.
 */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 200) String displayName
) {
}
