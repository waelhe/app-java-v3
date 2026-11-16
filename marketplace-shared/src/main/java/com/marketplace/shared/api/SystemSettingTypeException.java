package com.marketplace.shared.api;

/**
 * W0 ({@link SystemSettingsPort}): a {@code system_settings} row exists but its
 * JSON does not answer the type the reader asked for.
 *
 * <p><b>Why not a silent fallback:</b> a setting that cannot be parsed means the
 * control plane and the code disagree. Answering with a default would keep the
 * platform running on a value nobody chose — the failure mode the plan's
 * accountability rule exists to prevent («من غيّر النمط، ومتى، وإلى ماذا»). This
 * exception makes the disagreement loud: HTTP 500 on the surface, a clear line
 * in the log, and a {@code PATCH} to the right value heals it.
 *
 * <p>Callers that legitimately want a fallback use the port's {@code *OrDefault}
 * forms — the fallback then comes from the caller, visibly, and only for the
 * «no row» case.
 */
public class SystemSettingTypeException extends RuntimeException {

    public SystemSettingTypeException(String message) {
        super(message);
    }
}
