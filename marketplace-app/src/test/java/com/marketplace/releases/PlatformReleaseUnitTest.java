package com.marketplace.releases;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PlatformReleaseChannel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-18 unit contract pins — the locally-runnable layer of the release
 * surface (the journey gate itself is the docker-gated
 * {@code PlatformReleaseJourneyIntegrationTest}, CI the judge):
 * <ol>
 *   <li>The channel vocabulary parses case-insensitively and answers the
 *       house 400 (with the vocabulary listed) for anything outside it —
 *       never a silent default.</li>
 *   <li>The entity's publish factory enforces the V117 CHECK twins in Java
 *       (the defense-in-depth layer behind the boundary's bean validation):
 *       semver shapes on both version fields and a non-negative grace
 *       window.</li>
 * </ol>
 */
class PlatformReleaseUnitTest {

    @Test
    void channelVocabularyParsesAndRejectsLoudly() {
        assertThat(PlatformReleaseChannel.parse("android")).isEqualTo(PlatformReleaseChannel.ANDROID);
        assertThat(PlatformReleaseChannel.parse("IOS")).isEqualTo(PlatformReleaseChannel.IOS);
        assertThat(PlatformReleaseChannel.parse("Web")).isEqualTo(PlatformReleaseChannel.WEB);

        assertThatThrownBy(() -> PlatformReleaseChannel.parse("TVOS"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown release channel")
                .hasMessageContaining("ANDROID");
    }

    @Test
    void thePublishFactoryEnforcesTheCheckTwins() {
        // A well-formed release is born complete.
        PlatformRelease release = PlatformRelease.publish(UUID.randomUUID(),
                PlatformReleaseChannel.ANDROID, "3.14.1", "The neighborhood market.",
                "3.0.0", true, 72, Instant.parse("2026-10-08T00:00:00Z"));
        assertThat(release.getReleaseVersion()).isEqualTo("3.14.1");
        assertThat(release.getChannel()).isEqualTo(PlatformReleaseChannel.ANDROID);
        assertThat(release.getMinVersion()).isEqualTo("3.0.0");
        assertThat(release.isMandatory()).isTrue();
        assertThat(release.getGraceHours()).isEqualTo(72);
        assertThat(release.getPublishedAt()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));

        // The shape guards — the V117 CHECK twins, as Java.
        assertThatThrownBy(() -> PlatformRelease.publish(UUID.randomUUID(),
                PlatformReleaseChannel.IOS, "not-semver", "x", "1.0.0", false, 24, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic version");
        assertThatThrownBy(() -> PlatformRelease.publish(UUID.randomUUID(),
                PlatformReleaseChannel.IOS, "1.2.3", "x", "1.0", false, 24, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minVersion");
        assertThatThrownBy(() -> PlatformRelease.publish(UUID.randomUUID(),
                PlatformReleaseChannel.IOS, "1.2.3", "x", "1.0.0", false, -1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graceHours");
    }
}
