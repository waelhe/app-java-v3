package com.marketplace.admin;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * W0 (yelp-level plan §4.1 — «يُقرأ من كاش Redis نفسه»): the cached read path
 * over {@code system_settings}.
 *
 * <p><b>Why a separate bean from {@link SystemSettingsService}:</b> Spring's
 * cache advice is applied by a proxy, so a {@code @Cacheable} method invoked
 * through {@code this} is not intercepted at all — the classic self-invocation
 * hole. Keeping the cached method on its own collaborator makes every call an
 * inter-proxy call by construction, with no {@code @Lazy} self-injection to
 * explain.
 *
 * <p><b>The absent case is cached too</b> ({@code Optional.empty()} under the
 * key) — a missing setting is answered from the cache on the platform's hot
 * paths, and the writer evicts the key on <em>create</em> as well as on update,
 * so the first read after a new row lands sees it.
 *
 * <p>{@code Optional} and the entity both satisfy the cache's serialization
 * contract ({@code BaseEntity} implements {@code Serializable} — the documented
 * {@code ColdCacheRedisSerializationIntegrationTest} requirement).
 */
@Component
public class SystemSettingReader {

    /** The 16th named cache (application.yml {@code spring.cache.cache-names}). */
    public static final String CACHE_NAME = "system-settings";

    private final SystemSettingRepository repository;

    public SystemSettingReader(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "#key")
    public Optional<SystemSetting> find(String key) {
        return repository.findBySettingKey(key);
    }
}
