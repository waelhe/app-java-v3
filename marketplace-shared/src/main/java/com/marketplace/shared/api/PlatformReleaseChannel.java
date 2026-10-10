package com.marketplace.shared.api;

/**
 * A-18 (compliance plan C.12 — «نظام تحديثات المنصة»): the release channel,
 * the closed vocabulary of the platform-release contract. One enum keeps the
 * admin publish surface, the public boot path, the entity, and the fan-out
 * event compile-checked against one vocabulary instead of four string
 * literals that can drift — the exact silent-drift class {@link ReviewMode}
 * exists to close (same home, same reasoning).
 *
 * <p><b>Fail loud, never fall back:</b> {@link #parse(String)} answers an
 * unknown value with the house 400 listing the valid vocabulary (the
 * ModerationAdminController parseCategory convention — parse at the boundary,
 * BEFORE any service call), never a silent default channel.
 */
public enum PlatformReleaseChannel {

    /** The Android app line. */
    ANDROID,

    /** The iOS app line. */
    IOS,

    /** The web client line. */
    WEB;

    /**
     * Parses the wire value, answering the house 400 (with the vocabulary
     * listed) for anything outside it — the boundary-parse convention.
     */
    public static PlatformReleaseChannel parse(String value) {
        for (PlatformReleaseChannel channel : values()) {
            if (channel.name().equalsIgnoreCase(value)) {
                return channel;
            }
        }
        throw new BadRequestException(
                "Unknown release channel: " + value + " (valid channels: ANDROID, IOS, WEB)");
    }
}
