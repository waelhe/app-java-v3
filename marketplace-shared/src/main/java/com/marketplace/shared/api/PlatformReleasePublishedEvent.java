package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A-18 (compliance plan C.12 — «نظام تحديثات المنصة»): the platform
 * published a release — the fan-out event that carries the publication to
 * the notifications module («الفان-آوت حدث Modulith ← notifications»).
 *
 * <p><b>Why an event and not a call:</b> the publisher (the console's release
 * surface) owns the DECISION that a release is out; every consumer owns its
 * own reaction. The plan records the notifications consumer as Track B's
 * registered late arrival («المستهلك بيد B عبر السجل — الواصل المتأخر») —
 * exactly the {@link SystemSettingChangedEvent} shape: the decision is
 * published here, decoupled from the discovery, and the event publication
 * registry guards the delivery (the A-13 machinery) so the late-arriving
 * consumer's contract is already standing when it lands.
 *
 * <p><b>The payload is the boot contract's own facts</b> (channel, version,
 * the minimum-version floor, the mandatory flag, the grace window, the
 * publication instant): a consumer that only needs to alert "a new version
 * is available" never has to read the table back.
 */
public record PlatformReleasePublishedEvent(
        UUID releaseId,
        PlatformReleaseChannel channel,
        String version,
        String minVersion,
        boolean mandatory,
        int graceHours,
        Instant publishedAt) {
}
