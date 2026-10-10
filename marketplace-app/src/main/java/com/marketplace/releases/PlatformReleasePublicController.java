package com.marketplace.releases;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PlatformReleaseChannel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-18 (compliance plan C.12 — «نظام تحديثات المنصة»): the public release
 * path — «المسار العام للإصدار عند إقلاع العميل» (the §7/2 moment: the
 * first screen / bootstrap settings read). The client calls this at boot,
 * before any authenticated session exists, which is exactly why the surface
 * is a permitAll GET in {@code SecurityConfig} (the sitemap/robots and
 * public-catalog precedent family).
 *
 * <p><b>The boot contract:</b> the latest published release for the calling
 * client's channel — version · changelog · the minimum-version floor · the
 * mandatory flag · the grace window · the publication instant. The client's
 * own logic compares its running version against the floor and the
 * published version and decides its update UX; the server's contract is
 * the honest facts, nothing more.
 *
 * <p><b>Two faces of one contract</b> (the plan's own reasoning, recorded
 * here so the relationship stays visible): app updates travel as DATA on
 * this path; API-version deprecation travels as the official
 * {@code Deprecation}/{@code Sunset} response headers on the versioned API
 * surface (A-08's {@code ApiVersioningConfig}, the RFC 9745/8594 mechanism).
 * Both are how the platform moves its clients forward — the app-update
 * face is this path's own scope.
 *
 * <p><b>No release yet answers 404</b> (the house taxonomy — the honest
 * "nothing published for this channel", never a fabricated empty
 * contract); an unknown channel answers the house 400 with the vocabulary
 * listed (the boundary-parse convention, before any service call).
 */
@RestController
@RequestMapping(value = ApiConstants.RELEASES, version = "1.0")
public class PlatformReleasePublicController {

    private final PlatformReleaseService releases;

    public PlatformReleasePublicController(PlatformReleaseService releases) {
        this.releases = releases;
    }

    @GetMapping("/latest")
    @Operation(summary = "The latest platform release for a channel",
            description = "The boot-time read (the client's first-screen settings moment): the "
                    + "latest published release facts for the calling client's channel — the "
                    + "running client compares its version against the minimum-version floor and "
                    + "the published version to drive its update UX. 404 when nothing has been "
                    + "published for the channel yet; 400 for an unknown channel.")
    public ResponseEntity<PlatformReleaseService.PlatformReleaseView> latest(
            @Parameter(description = "The client's channel (ANDROID, IOS or WEB)")
            @RequestParam String channel) {
        return ResponseEntity.ok(releases.latest(PlatformReleaseChannel.parse(channel)));
    }
}
