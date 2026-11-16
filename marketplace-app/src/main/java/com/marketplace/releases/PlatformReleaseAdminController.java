package com.marketplace.releases;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PlatformReleaseChannel;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * A-18 (compliance plan C.12 — «نظام تحديثات المنصة»): the console's
 * release-publication surface — the manager publishes a release here
 * («ينشرها المدير من console»).
 *
 * <p><b>Three-layer authorization</b> (the {@code ModerationAdminController}
 * pattern verbatim): the chain's own {@code /api/v1/admin/** ->
 * hasRole("ADMIN")} rule in {@code SecurityConfig} + this class-level
 * {@code @PreAuthorize} + the service-level gate as defense in depth.
 *
 * <p><b>The boundary parses before any service call</b> (the
 * parseCategory convention): the channel arrives as a String and parses to
 * the {@link PlatformReleaseChannel} vocabulary with the house 400 listing
 * it — an invalid value never reaches the service. The version shapes are
 * pinned by bean validation at the boundary (the V117 CHECK twins — one
 * contract, three guards).
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN + "/releases", version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
@NamedInterface("admin-api")
public class PlatformReleaseAdminController {

    private final PlatformReleaseService releases;

    public PlatformReleaseAdminController(PlatformReleaseService releases) {
        this.releases = releases;
    }

    /**
     * The publication body — the plan's own column list: channel · version ·
     * changelog · the minimum-version floor · the mandatory flag · the grace
     * window («نافذة التوفير», hours the user may defer).
     */
    public record PublishReleaseRequest(
            @NotBlank String channel,
            @NotBlank @Pattern(regexp = "^[0-9]+\\.[0-9]+\\.[0-9]+$",
                    message = "version must be a semantic version (major.minor.patch)") String version,
            @NotBlank @Size(max = 10_000) String changelog,
            @NotBlank @Pattern(regexp = "^[0-9]+\\.[0-9]+\\.[0-9]+$",
                    message = "minVersion must be a semantic version (major.minor.patch)") String minVersion,
            @NotNull Boolean mandatory,
            @NotNull @Min(0) @Max(8760) Integer graceHours) {
    }

    @PostMapping
    @Operation(summary = "Publish a platform release",
            description = "Records one platform release for the given channel and fans the "
                    + "PlatformReleasePublishedEvent out through the Modulith event publication "
                    + "registry (the notifications module's standing contract). Publishing a "
                    + "(channel, version) pair that already exists answers 409 — release "
                    + "identities are never recycled; superseding is the next publish.")
    public ResponseEntity<PlatformReleaseService.PlatformReleaseView> publish(
            @Valid @RequestBody PublishReleaseRequest request,
            Authentication authentication) {
        PlatformReleaseChannel channel = PlatformReleaseChannel.parse(request.channel());
        PlatformReleaseService.PlatformReleaseView view = releases.publish(channel,
                request.version(), request.changelog(), request.minVersion(),
                request.mandatory(), request.graceHours(),
                authentication != null ? authentication.getName() : null);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(view);
    }

    @GetMapping
    @Operation(summary = "List the platform releases",
            description = "The publication history, newest first, optionally filtered by channel "
                    + "— the console's release ledger.")
    public ResponseEntity<PagedResponse<PlatformReleaseService.PlatformReleaseView>> list(
            @RequestParam(required = false) String channel,
            Pageable pageable) {
        PlatformReleaseChannel parsed = channel == null ? null : PlatformReleaseChannel.parse(channel);
        return ResponseEntity.ok(PagedResponse.of(releases.list(parsed, pageable)));
    }

}
