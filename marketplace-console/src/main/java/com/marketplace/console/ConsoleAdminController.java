package com.marketplace.console;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * B-15 (compliance plan C.5): the console's surface — the
 * {@code NeighborhoodVerificationAdminController} house shape (the
 * class-level {@code @PreAuthorize("hasRole('ADMIN')")} gate — the
 * Security method-security reference's own channel): the tri-partite
 * view (the console check), the flags and the remote config (the
 * operator's writes and boards), and the two READS (the metrics summary
 * from the {@code MeterRegistry}, the change history from the rows' own
 * Data JPA auditing fields).
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class ConsoleAdminController {

    private final ConsoleService consoleService;

    public ConsoleAdminController(ConsoleService consoleService) {
        this.consoleService = consoleService;
    }

    @GetMapping("/console")
    @Operation(summary = "The console view (the tri-partite check)",
            description = "The console's own structure — التشغيل/المحتوى/النظام with the surfaces each "
                    + "section links to. The catalog is the measured inventory of the EXISTING admin "
                    + "endpoints: a capability not present in code stays development and is never "
                    + "listed (the identity statement's active limit).")
    public ResponseEntity<ConsoleView> view() {
        return ResponseEntity.ok(ConsoleView.of());
    }

    @GetMapping("/console/flags")
    @Operation(summary = "The feature flags board",
            description = "The operator's inventory — every live flag with its state and its change "
                    + "metadata (the Data JPA auditing fields).")
    public ResponseEntity<List<FeatureFlagResponse>> flags() {
        return ResponseEntity.ok(consoleService.flags().stream().map(FeatureFlagResponse::from).toList());
    }

    @PostMapping("/console/flags")
    @Operation(summary = "Register a feature flag",
            description = "A named operational switch the services read at request time — never a "
                    + "boot-time conditional. A duplicate live key answers 409.")
    public ResponseEntity<FeatureFlagResponse> registerFlag(@Valid @RequestBody FlagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FeatureFlagResponse.from(
                        consoleService.registerFlag(request.key(), request.description(), request.enabled())));
    }

    @PatchMapping("/console/flags/{key}")
    @Operation(summary = "Flip a feature flag",
            description = "The operator's flip — the auditing fields record who and when. Unknown key 404.")
    public ResponseEntity<FeatureFlagResponse> flip(
            @PathVariable String key, @Valid @RequestBody FlagFlipRequest request) {
        return ResponseEntity.ok(FeatureFlagResponse.from(consoleService.setFlag(key, request.enabled())));
    }

    @GetMapping("/console/config")
    @Operation(summary = "The remote config board",
            description = "The operator's inventory — every live config value with its change metadata.")
    public ResponseEntity<List<RemoteConfigResponse>> configs() {
        return ResponseEntity.ok(consoleService.configs().stream().map(RemoteConfigResponse::from).toList());
    }

    @PostMapping("/console/config")
    @Operation(summary = "Register a remote config value",
            description = "The operational half of the Boot properties design — a value the operator "
                    + "changes from the console, read at request time. A duplicate live key answers 409.")
    public ResponseEntity<RemoteConfigResponse> registerConfig(@Valid @RequestBody ConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RemoteConfigResponse.from(
                        consoleService.registerConfig(request.key(), request.value(), request.description())));
    }

    @PatchMapping("/console/config/{key}")
    @Operation(summary = "Revise a remote config value",
            description = "The operator's revision — the auditing fields record who and when. Unknown key 404.")
    public ResponseEntity<RemoteConfigResponse> revise(
            @PathVariable String key, @Valid @RequestBody ConfigRequest request) {
        return ResponseEntity.ok(RemoteConfigResponse.from(
                consoleService.reviseConfig(key, request.value(), request.description())));
    }

    @GetMapping("/console/metrics")
    @Operation(summary = "The metrics summary (read)",
            description = "The MeterRegistry the app's @Observed commands already populate — every meter "
                    + "that EXISTS at query time, name + type + its own reading. The Actuator channel's "
                    + "own data, never fabricated: an empty registry answers an honest empty list.")
    public ResponseEntity<List<ConsoleService.MeterSummary>> metrics() {
        return ResponseEntity.ok(consoleService.metrics());
    }

    @GetMapping("/console/geo-settings")
    @Operation(summary = "The geographic feature settings board",
            description = "B-18 (C.10): the operator's geographic inventory — every live (feature key, geo "
                    + "scope, value) row. The resolution contract: the most specific scope wins "
                    + "(«الأخص يغلب الأعم»), then the global flag, then the fail-closed default.")
    public ResponseEntity<List<GeographicSettingResponse>> geographicSettings() {
        return ResponseEntity.ok(consoleService.geographicSettings().stream()
                .map(GeographicSettingResponse::from).toList());
    }

    @PostMapping("/console/geo-settings")
    @Operation(summary = "Register a geographic feature setting",
            description = "B-18 (C.10): one feature-setting row scoped to one geo hierarchy node (بلد ← مدينة "
                    + "← حي). The location resolves through the geo port first (unknown 404, before any "
                    + "write); a duplicate live (key, location) pair answers 409. Read at request time by "
                    + "the location-aware services — never a boot-time conditional.")
    public ResponseEntity<GeographicSettingResponse> registerGeographicSetting(
            @Valid @RequestBody GeographicSettingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GeographicSettingResponse.from(consoleService.registerGeographicSetting(
                        request.key(), request.locationId(), request.enabled())));
    }

    @PatchMapping("/console/geo-settings/{key}/{locationId}")
    @Operation(summary = "Flip a geographic feature setting",
            description = "The operator's flip at one scope — the auditing fields record who and when. "
                    + "Unknown (key, location) pair answers 404.")
    public ResponseEntity<GeographicSettingResponse> flipGeographicSetting(
            @PathVariable String key, @PathVariable UUID locationId,
            @Valid @RequestBody FlagFlipRequest request) {
        return ResponseEntity.ok(GeographicSettingResponse.from(
                consoleService.setGeographicSetting(key, locationId, request.enabled())));
    }

    @GetMapping("/console/geo-settings/effective")
    @Operation(summary = "The effective feature gate at a location (read)",
            description = "B-18 (C.10) — the resolution's own proof surface: the nearest ancestor's live row "
                    + "(the neighborhood beats the city beats the country), then the global flag, then the "
                    + "fail-closed default. The same service-layer read the location-aware services call at "
                    + "request time — never a boot-time conditional (the C.10 measured limit).")
    public ResponseEntity<EffectiveGateResponse> effectiveGate(
            @RequestParam String key, @RequestParam UUID locationId) {
        return ResponseEntity.ok(new EffectiveGateResponse(key, locationId,
                consoleService.isEnabled(key, locationId)));
    }

    @GetMapping("/console/audit")
    @Operation(summary = "The change history (read)",
            description = "The flags' and the config rows' own Data JPA auditing fields — who registered, "
                    + "who changed, when — the console's own trail, newest first.")
    public ResponseEntity<List<ConsoleService.ChangeHistoryEntry>> audit() {
        return ResponseEntity.ok(consoleService.changeHistory());
    }

    /** The flag registration body. */
    public record FlagRequest(
            @NotBlank @Size(max = 200)
            @Schema(description = "The flag's key — the services' lookup name.", example = "community.polls.enabled")
            String key,
            @Size(max = 2000)
            @Schema(description = "What the flag gates — the operator's own documentation.")
            String description,
            @NotNull
            @Schema(description = "The flag's initial state.")
            boolean enabled
    ) {
    }

    /** The flip body. */
    public record FlagFlipRequest(
            @NotNull
            @Schema(description = "The flag's new state.")
            boolean enabled
    ) {
    }

    /** The config registration/revision body. */
    public record ConfigRequest(
            @NotBlank @Size(max = 200)
            @Schema(description = "The config's key — the services' lookup name.", example = "community.posts.max-length")
            String key,
            @NotBlank @Size(max = 2000)
            @Schema(description = "The config's value — the raw string; the consumer parses its own type.", example = "2000")
            String value,
            @Size(max = 2000)
            @Schema(description = "What the value calibrates — the operator's own documentation.")
            String description
    ) {
    }

    /** B-18 (C.10): the geographic setting registration body. */
    public record GeographicSettingRequest(
            @NotBlank @Size(max = 200)
            @Schema(description = "The feature's key — the same lookup name the services gate on.",
                    example = "community.polls.enabled")
            String key,
            @NotNull
            @Schema(description = "The geo hierarchy node this row scopes (بلد ← مدينة ← حي) — resolved "
                    + "through the geo port; unknown answers 404 before any write.")
            UUID locationId,
            @NotNull
            @Schema(description = "The feature-gate value at this scope.")
            boolean enabled
    ) {
    }

    /** B-18 (C.10): the geographic setting's read model. */
    public record GeographicSettingResponse(
            @Schema(description = "The feature's key.") String key,
            @Schema(description = "The geo scope this row pins.") UUID locationId,
            @Schema(description = "The feature-gate value at this scope.") boolean enabled,
            @Schema(description = "Who last changed it (the auditing field).") String updatedBy,
            @Schema(description = "When it was last changed (the auditing field).") java.time.Instant updatedAt
    ) {

        static GeographicSettingResponse from(GeographicFeatureSetting setting) {
            return new GeographicSettingResponse(setting.getKey(), setting.getLocationId(),
                    setting.isEnabled(), setting.getUpdatedBy(), setting.getUpdatedAt());
        }
    }

    /** B-18 (C.10): the effective gate at a location — the resolution's own proof surface. */
    public record EffectiveGateResponse(
            @Schema(description = "The feature's key.") String key,
            @Schema(description = "The location the gate was resolved at.") UUID locationId,
            @Schema(description = "The effective value: the nearest ancestor's row, then the global flag, "
                    + "then the fail-closed default.") boolean enabled
    ) {
    }

    /** The flag's read model. */
    public record FeatureFlagResponse(
            @Schema(description = "The flag's key.") String key,
            @Schema(description = "What the flag gates.") String description,
            @Schema(description = "The flag's state.") boolean enabled,
            @Schema(description = "Who last changed it (the auditing field).") String updatedBy,
            @Schema(description = "When it was last changed (the auditing field).") java.time.Instant updatedAt
    ) {

        static FeatureFlagResponse from(FeatureFlag flag) {
            return new FeatureFlagResponse(flag.getKey(), flag.getDescription(), flag.isEnabled(),
                    flag.getUpdatedBy(), flag.getUpdatedAt());
        }
    }

    /** The config's read model. */
    public record RemoteConfigResponse(
            @Schema(description = "The config's key.") String key,
            @Schema(description = "The config's value.") String value,
            @Schema(description = "What the value calibrates.") String description,
            @Schema(description = "Who last changed it (the auditing field).") String updatedBy,
            @Schema(description = "When it was last changed (the auditing field).") java.time.Instant updatedAt
    ) {

        static RemoteConfigResponse from(RemoteConfigValue config) {
            return new RemoteConfigResponse(config.getKey(), config.getValue(), config.getDescription(),
                    config.getUpdatedBy(), config.getUpdatedAt());
        }
    }
}
