package com.marketplace.identity.spi;

import com.marketplace.shared.api.UserSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;

/**
 * SPI for cross-module access to identity/user operations.
 */
@NamedInterface("identity-spi")
public interface IdentitySpi {

    Page<UserSummary> findAllSummaries(Pageable pageable);
}
