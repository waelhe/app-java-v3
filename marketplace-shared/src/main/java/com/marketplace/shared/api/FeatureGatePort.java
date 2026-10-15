package com.marketplace.shared.api;

/**
 * The request-time feature gate — the cross-module seam the domain
 * services call to ask whether a capability is enabled NOW (never a
 * boot-time conditional: the C.10 measured limit verbatim — Boot's
 * external configuration governs the STATIC at boot exclusively, the
 * operational is DATA read at request time).
 *
 * <p><b>The two-half design (the {@code configValue(key, fallback)}
 * contract's own shape):</b> the caller carries the FALLBACK — the
 * static default that names the capability's built behavior — and the
 * console's row is the OPERATIONAL override. A key with a live row
 * answers the row's state; a key with NO row answers the caller's
 * fallback, so an unseeded environment keeps the built behavior
 * exactly (the E2E-honest adoption: the lever is armed by the Day-1
 * seed, never by breaking the default).</p>
 *
 * <p><b>The seam's own precedent:</b> {@link GeoLookupPort} — the port
 * interface lives in shared-api, the implementation is wired by the
 * module that owns the data (the console's {@code ConsoleService} —
 * the flags' own request-time read), and the consuming modules declare
 * {@code shared} only (the Modulith boundary stays flat). The console
 * module never depends on a domain module and no domain module depends
 * on the console — the vocabulary crosses through this port alone.</p>
 *
 * <p>Implementations must be cheap point reads (a flag row lookup),
 * safe to call on every request, and honest about an absent row (the
 * fallback — never an exception: an unregistered key is a policy
 * question, not a server fault).</p>
 */
public interface FeatureGatePort {

    /**
     * The request-time gate — the answer for THIS key, NOW.
     *
     * @param key      the feature's lookup name (e.g. {@code community.events.enabled}
     *                 — the console's registered vocabulary)
     * @param fallback the caller's own static default: what the built
     *                 behavior is when no operational row exists
     * @return the live row's state, or the fallback when no row exists
     */
    boolean isEnabled(String key, boolean fallback);
}
