package com.marketplace.admin;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewMode;
import com.marketplace.shared.api.SystemSettingChangedEvent;
import com.marketplace.shared.api.SystemSettingKeys;
import com.marketplace.shared.api.SystemSettingsPort;
import com.marketplace.shared.api.SystemSettingTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * W0 (yelp-level plan §4.6 — «التبديل بلا كود»): the platform's setting adapter —
 * the disclosed read port ({@link SystemSettingsPort}) plus the administrative
 * write path.
 *
 * <p><b>Three guard layers (the plan's own requirement):</b> the URL space
 * ({@code /api/v1/admin/**} → {@code hasRole('ADMIN')} in {@code SecurityConfig}),
 * the class-level {@code @PreAuthorize} on {@code AdminController}, and this
 * service — the only place allowed to touch the store. A writer that bypasses
 * HTTP cannot bypass the service.
 *
 * <p><b>Reads go through the cache; writes never do.</b> {@link SystemSettingReader}
 * serves the {@code @Cacheable} path; the write path reads the row from the
 * repository so it always mutates current state under the optimistic-lock
 * {@code version} ({@code BaseEntity}).
 *
 * <p><b>Invalidation (the {@code CacheInvalidationRelay} channel — the eighth
 * service to use it):</b> the eviction request is published <em>inside</em> the
 * transaction and the relay executes it AFTER_COMMIT, so a concurrent reader can
 * never repopulate the cache with the pre-commit value. The relay's own
 * documentation carries the Spring reference for that rule.
 *
 * <p><b>Audit:</b> the entity is {@code @Audited}, so Envers records who changed
 * the value and when ({@code system_settings_aud}); the log line records the same
 * fact in the operational channel; the event carries it to any interested module.
 */
@Service
public class SystemSettingsService implements SystemSettingsPort {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingsService.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SystemSettingRepository repository;
    private final SystemSettingReader reader;
    private final ApplicationEventPublisher events;

    public SystemSettingsService(SystemSettingRepository repository,
                                 SystemSettingReader reader,
                                 ApplicationEventPublisher events) {
        this.repository = repository;
        this.reader = reader;
        this.events = events;
    }

    // -- The read port ({@link SystemSettingsPort}) ----------------------------

    @Override
    public Optional<String> getString(String key) {
        return reader.find(key).map(SystemSetting::textValue);
    }

    @Override
    public OptionalInt getInt(String key) {
        Optional<SystemSetting> setting = reader.find(key);
        return setting.isEmpty() ? OptionalInt.empty() : OptionalInt.of(setting.get().intValue());
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        return reader.find(key).map(SystemSetting::booleanValue);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return reader.find(key).map(setting -> setting.as(type));
    }

    // -- The administrative surface -------------------------------------------

    @Transactional(readOnly = true)
    public Page<SystemSetting> findAll(Pageable pageable) {
        return repository.findAllOrdered(pageable);
    }

    /**
     * The single-row read. Deliberately not cached: the admin surface reads it
     * once per request, and a stale answer here would be a stale answer on the
     * very screen an operator uses to switch platform behaviour.
     */
    @Transactional(readOnly = true)
    public SystemSetting findByKey(String key) {
        return repository.findBySettingKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("Setting '" + key + "' does not exist"));
    }

    /**
     * Creates a key. A duplicate is a 409, not an upsert: creation is how an
     * operator introduces a <em>new</em> control, and silently overwriting an
     * existing one on a typo'd create would change platform behaviour without
     * anyone asking for it.
     */
    @Transactional
    public SystemSetting create(String key, JsonNode value, String description, String actor) {
        if (repository.findBySettingKey(key).isPresent()) {
            throw new ConflictException("Setting '" + key + "' already exists — PATCH it instead");
        }
        validateKnownValue(key, value);
        SystemSetting created = repository.saveAndFlush(
                SystemSetting.create(UUID.randomUUID(), key, value, description));
        log.info("System setting '{}' created as {} by {}", key, created.getRawJson(), actor);
        evict(key);
        events.publishEvent(new SystemSettingChangedEvent(
                key, null, plainValue(created.value()), actor, Instant.now()));
        return created;
    }

    /**
     * Updates value and/or description. A change that changes nothing is a no-op
     * down to the cache: no revision in {@code system_settings_aud}, no eviction,
     * no event ({@link SystemSetting#replaceValue} is the decider).
     */
    @Transactional
    public SystemSetting update(String key, JsonNode value, String description, String actor) {
        SystemSetting setting = repository.findBySettingKey(key)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Setting '" + key + "' does not exist — create it instead"));
        JsonNode before = setting.value();
        if (value != null) {
            validateKnownValue(key, value);
        }
        boolean valueChanged = value != null && setting.replaceValue(value);
        boolean descriptionChanged = description != null && setting.replaceDescription(description);
        if (!valueChanged && !descriptionChanged) {
            return setting;
        }
        repository.saveAndFlush(setting);
        evict(key);
        if (valueChanged) {
            log.info("System setting '{}' changed from {} to {} by {}",
                    key, before, setting.getRawJson(), actor);
            events.publishEvent(new SystemSettingChangedEvent(
                    key, plainValue(before), plainValue(setting.value()), actor, Instant.now()));
        }
        return setting;
    }

    /**
     * The relay's own two-step rule stated once: request inside the transaction,
     * eviction after commit. Called on create as well as update — the reader
     * caches the absent case too, so a new row must evict its key to be seen.
     */
    private void evict(String key) {
        events.publishEvent(new CacheInvalidationRequested(Set.of(SystemSettingReader.CACHE_NAME), key));
    }

    /** JSON natives for the event payload — no Jackson types leave this module. */
    private static Object plainValue(JsonNode node) {
        return JSON.convertValue(node, Object.class);
    }

    /**
     * W1: the write-time vocabulary gate for the settings whose readers
     * parse into a Java vocabulary — a typo must not be storable, because
     * the reader's contract is fail-loud (the W0 no-silent-fallback rule):
     * <ul>
     *   <li>{@code reviews.mode} — a JSON string that must parse as a
     *       {@link ReviewMode} (the reviews gate would otherwise fail on
     *       every later creation);</li>
     *   <li>{@code reviews.organic.daily-cap} — a JSON number, a positive
     *       integer.</li>
     * </ul>
     * Unknown keys are deliberately not validated here — the key space
     * stays open for future waves (the {@code chk_system_settings_key}
     * format guard is their only constraint until each gains its
     * vocabulary). A rejected write answers the house 400 with the same
     * message the reader would have failed with.
     */
    private static void validateKnownValue(String key, JsonNode value) {
        if (SystemSettingKeys.REVIEWS_MODE.equals(key)) {
            if (!value.isTextual()) {
                throw new BadRequestException("Setting '" + key + "' must be a JSON string");
            }
            try {
                ReviewMode.parse(value.asText());
            } catch (SystemSettingTypeException unknown) {
                throw new BadRequestException(unknown.getMessage());
            }
        } else if (SystemSettingKeys.REVIEWS_ORGANIC_DAILY_CAP.equals(key)) {
            if (!value.isIntegralNumber() || value.asInt() < 1) {
                throw new BadRequestException(
                        "Setting '" + key + "' must be a positive integer (JSON number)");
            }
        }
    }
}
