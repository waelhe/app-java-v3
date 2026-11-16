package com.marketplace.shared.api;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * W0 (yelp-level plan §4.6 — «التبديل بلا كود»): the disclosed read port for
 * platform settings.
 *
 * <p><b>Port here, adapter in the admin module (the {@code ReviewStatsPort} /
 * {@code BookingInfoPort} pattern, SYSTEM.md §6):</b> consumers such as the W1
 * review-creation gate depend on {@code shared :: shared-api} only — never on
 * the module that owns the {@code system_settings} table. Modulith then refuses
 * any consumer that reaches past this interface.
 *
 * <p><b>Caching (plan §4.1: «يُقرأ من كاش Redis نفسه»):</b> the adapter caches
 * reads and evicts through {@link CacheInvalidationRequested} AFTER_COMMIT, so
 * an admin {@code PATCH} is visible on every replica within one hop — no
 * redeploy, which is the literal meaning of the owner's «أحدد أيهما يفعّل».
 * The port deliberately makes no caching promise: caching is the adapter's
 * mechanism, not the consumer's contract.
 *
 * <p><b>Types:</b> stored values are JSON, so the port offers typed accessors
 * rather than one raw string. An accessor that finds no row returns empty; a row
 * whose JSON cannot be read as the requested type raises
 * {@link SystemSettingTypeException} — never a silent fallback, because a
 * setting that fails to parse must not be answered with a value nobody chose.
 * Readers that must not fail use the {@code *OrDefault} forms below, which take
 * their default from the reader and keep the platform's own default as the
 * fallback.
 */
public interface SystemSettingsPort {

    Optional<String> getString(String key);

    OptionalInt getInt(String key);

    Optional<Boolean> getBoolean(String key);

    /**
     * Structured settings (objects/arrays — channel policies, revenue engines).
     * The requested type is the JSON shape's Java counterpart; primitives,
     * enums, records, maps and collections are all valid targets.
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * The admission read — authoritative, never cached (agent findings
     * 2026-10-26T13:11 export, row 15). A policy that DECIDES whether a
     * write is admitted must read the committed row, not the cache: the
     * cached path's own documented race — a read that began before an
     * administrator's change commits can populate the cache AFTER the
     * change's AFTER_COMMIT eviction (the {@code @Cacheable}
     * put-after-evict window), and every later request then trusts the
     * obsolete policy, admitting writes the platform already restricted.
     * The {@link FeatureGatePort} precedent verbatim: a request-time
     * decision gate is a cheap point read, safe to call on every request,
     * honest about an absent row — never a cache hop. Benign read
     * surfaces (display, reporting) keep the cached accessors above.
     */
    Optional<String> getStringAuthoritative(String key);

    default String getStringOrDefault(String key, String defaultValue) {
        return getString(key).orElse(defaultValue);
    }

    /** The admission read's own default form — the caller's built behavior. */
    default String getStringAuthoritativeOrDefault(String key, String defaultValue) {
        return getStringAuthoritative(key).orElse(defaultValue);
    }

    default int getIntOrDefault(String key, int defaultValue) {
        return getInt(key).orElse(defaultValue);
    }

    default boolean getBooleanOrDefault(String key, boolean defaultValue) {
        return getBoolean(key).orElse(defaultValue);
    }
}
