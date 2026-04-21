package com.marketplace.identity.spi;

import com.marketplace.shared.api.UserSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * SPI for cross-module access to identity/user operations.
 */
public interface IdentitySpi {

	Page<UserSummary> findAllSummaries(Pageable pageable);
}
