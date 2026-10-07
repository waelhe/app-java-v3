package com.marketplace.console;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.observation.annotation.Observed;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * B-15 (compliance plan C.5): the console's engine — the flags and the
 * remote config (the operational DATA half, read at request time), the
 * registration/update surfaces (the operator's writes, guarded by the
 * controller's ADMIN gate), and the console's own READS: the metrics
 * summary (the {@code MeterRegistry} the app's {@code @Observed}
 * commands already populate — what EXISTS at query time, never
 * fabricated) and the change history (the rows' own Data JPA auditing
 * fields — who changed what, when).
 *
 * <p><b>The §0.1 active limit, embodied everywhere:</b> the console
 * configures and reads; it never builds. A flag names an EXISTING
 * capability; a config row calibrates an EXISTING reader; the metrics
 * read summarizes EXISTING observations.</p>
 */
@Service
@Transactional
public class ConsoleService {

    private final FeatureFlagRepository flagRepository;
    private final RemoteConfigValueRepository configRepository;
    private final ConsoleProperties properties;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public ConsoleService(FeatureFlagRepository flagRepository,
                          RemoteConfigValueRepository configRepository,
                          ConsoleProperties properties,
                          ObjectProvider<MeterRegistry> meterRegistry) {
        this.flagRepository = flagRepository;
        this.configRepository = configRepository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * The request-time flag read — THE gate the services call. A key
     * with a live row answers the row's state; a key with NO row
     * answers the policy default ({@link ConsoleProperties.Flags
     * #defaultEnabled()} — fail-closed by the house's conservative
     * default: an unregistered flag is OFF, a typo'd key can never
     * silently enable a capability).
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        return flagRepository.findByKey(key)
                .map(FeatureFlag::isEnabled)
                .orElseGet(properties.flags()::defaultEnabled);
    }

    /**
     * The request-time config read — the operational value with the
     * caller's own fallback (the static half's value: the Boot-bound
     * default the {@code @DefaultValue} carries, exactly the two-half
     * design — the environment's static default, the console's
     * operational override).
     */
    @Transactional(readOnly = true)
    public String configValue(String key, String fallback) {
        return configRepository.findByKey(key)
                .map(RemoteConfigValue::getValue)
                .orElse(fallback);
    }

    /** The registration — a duplicate live key answers 409 (the V157 unique's polite face). */
    @Observed(name = "console.flag.register")
    public FeatureFlag registerFlag(String key, String description, boolean enabled) {
        if (flagRepository.findByKey(key).isPresent()) {
            throw new ConflictException("Feature flag already registered: " + key);
        }
        return flagRepository.save(FeatureFlag.register(key, description, enabled));
    }

    /** The flip — unknown key 404; the auditing fields record who and when. */
    @Observed(name = "console.flag.update")
    public FeatureFlag setFlag(String key, boolean enabled) {
        FeatureFlag flag = flagRepository.findByKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("Feature flag not found: " + key));
        flag.setEnabled(enabled);
        return flag;
    }

    /** The flags board — the operator's inventory. */
    @Transactional(readOnly = true)
    public List<FeatureFlag> flags() {
        return flagRepository.findAll();
    }

    /** The config registration — a duplicate live key answers 409. */
    @Observed(name = "console.config.register")
    public RemoteConfigValue registerConfig(String key, String value, String description) {
        if (configRepository.findByKey(key).isPresent()) {
            throw new ConflictException("Remote config already registered: " + key);
        }
        return configRepository.save(RemoteConfigValue.register(key, value, description));
    }

    /** The value revision — unknown key 404; the auditing fields record who and when. */
    @Observed(name = "console.config.update")
    public RemoteConfigValue reviseConfig(String key, String value, String description) {
        RemoteConfigValue config = configRepository.findByKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("Remote config not found: " + key));
        config.revise(value, description);
        return config;
    }

    /** The config board — the operator's inventory. */
    @Transactional(readOnly = true)
    public List<RemoteConfigValue> configs() {
        return configRepository.findAll();
    }

    /**
     * The metrics summary (the Actuator endpoints reference's own
     * channel — the {@code MeterRegistry} the app's observations
     * populate): every meter that EXISTS at query time, name + type +
     * its own reading — the command observations the platform's
     * {@code @Observed} inventory already produces. Empty when nothing
     * has been observed yet (an honest empty, never a fabricated zero).
     */
    @Transactional(readOnly = true)
    public List<MeterSummary> metrics() {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return List.of();
        }
        return registry.getMeters().stream()
                .map(meter -> new MeterSummary(
                        meter.getId().getName(),
                        meter.getId().getType().toString(),
                        java.util.stream.StreamSupport.stream(meter.measure().spliterator(), false)
                                .map(measurement -> new MeasurementSummary(
                                        measurement.getStatistic().toString(),
                                        measurement.getValue()))
                                .toList()))
                .sorted(java.util.Comparator.comparing(MeterSummary::name))
                .toList();
    }

    /**
     * The change history (the Data JPA auditing reference's own fields):
     * the flags' and the config rows' audit columns — who registered,
     * who flipped, when — the console's own trail, newest first.
     */
    @Transactional(readOnly = true)
    public List<ChangeHistoryEntry> changeHistory() {
        List<ChangeHistoryEntry> entries = new java.util.ArrayList<>();
        flagRepository.findAll().forEach(flag -> entries.add(ChangeHistoryEntry.of(flag)));
        configRepository.findAll().forEach(config -> entries.add(ChangeHistoryEntry.of(config)));
        entries.sort(java.util.Comparator.comparing(ChangeHistoryEntry::updatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return entries;
    }

    /** One meter's own reading — the summary the metrics surface serves. */
    public record MeasurementSummary(String statistic, double value) {
    }

    /** One meter in the registry — name, type, its measurements. */
    public record MeterSummary(String name, String type, List<MeasurementSummary> measurements) {
    }

    /** One audited row's change-history entry — the Data JPA auditing fields verbatim. */
    public record ChangeHistoryEntry(
            String kind, String key, String updatedBy, java.time.Instant updatedAt,
            String createdBy, java.time.Instant createdAt) {

        static ChangeHistoryEntry of(FeatureFlag flag) {
            return new ChangeHistoryEntry("FEATURE_FLAG", flag.getKey(),
                    flag.getUpdatedBy(), flag.getUpdatedAt(), flag.getCreatedBy(), flag.getCreatedAt());
        }

        static ChangeHistoryEntry of(RemoteConfigValue config) {
            return new ChangeHistoryEntry("REMOTE_CONFIG", config.getKey(),
                    config.getUpdatedBy(), config.getUpdatedAt(), config.getCreatedBy(), config.getCreatedAt());
        }
    }
}
