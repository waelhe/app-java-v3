package com.marketplace.shared.jpa;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables JPA auditing ({@code @CreatedBy}, {@code @LastModifiedBy}, etc.)
 * and Hibernate filters for soft delete.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
