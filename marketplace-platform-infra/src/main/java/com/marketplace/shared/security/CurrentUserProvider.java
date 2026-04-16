package com.marketplace.shared.security;

import org.springframework.security.core.Authentication;

import java.util.UUID;

public interface CurrentUserProvider {

    UUID getCurrentUserId(Authentication authentication);

    boolean isAdmin(Authentication authentication);
}
