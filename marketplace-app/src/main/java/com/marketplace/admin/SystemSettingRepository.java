package com.marketplace.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * W0: the {@code system_settings} store.
 *
 * <p>Lookups go by key — the key is the identity (V71's unique index), and the
 * soft-delete filter is applied by Hibernate's {@code @SoftDelete} on
 * {@code BaseEntity} exactly as every other repository here. {@code
 * findAllOrdered} exists because the admin surface must be stable across pages
 * while {@code Pageable.unpaged()} has no natural order to fall back on.
 */
public interface SystemSettingRepository extends JpaRepository<SystemSetting, UUID> {

    Optional<SystemSetting> findBySettingKey(String settingKey);

    @Query("select s from SystemSetting s order by s.settingKey asc")
    Page<SystemSetting> findAllOrdered(Pageable pageable);
}
