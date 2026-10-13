package com.marketplace.notifications.push;

/**
 * Stage 7 (plan D-10, ADR-0003): the push channel's delivery seam — the
 * notifications module's own channel implementation (no cross-module
 * consumers exist, so this is a module-internal port, not a shared/api
 * contract — the seam-exists-because-a-consumer-exists discipline).
 *
 * <p>The contract is per-token and honest: the caller learns WHICH tokens
 * failed so the registry can prune the ones the provider reports as
 * unregistered (the official FCM error class) — the self-healing token
 * registry, the plan's «فشل المزود قابل للاسترداد ومرئي».
 */
public interface PushNotificationPort {

    /**
     * The per-token result — the provider's own verdict.
     *
     * @param token   the device token the send targeted
     * @param success whether the provider accepted the message
     * @param unregistered whether the provider reports the token dead
     *                    (UNREGISTERED / INVALID_ARGUMENT class) — the
     *                    registry prunes exactly these
     */
    record PushResult(String token, boolean success, boolean unregistered) {
    }

    /**
     * Sends one message to each token (the provider's official batching
     * API under the adapter).
     *
     * @param tokens the device tokens
     * @param title  the message title
     * @param body   the message body
     * @return one result per input token, in order
     */
    java.util.List<PushResult> send(java.util.List<String> tokens, String title, String body);
}
