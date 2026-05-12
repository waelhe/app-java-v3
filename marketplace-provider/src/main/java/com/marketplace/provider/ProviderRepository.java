package com.marketplace.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProviderRepository extends JpaRepository<ProviderProfile, UUID> {
}
