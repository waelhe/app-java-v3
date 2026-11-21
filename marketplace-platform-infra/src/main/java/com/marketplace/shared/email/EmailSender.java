package com.marketplace.shared.email;

import java.util.Map;

/**
 * Transport-neutral transactional email contract.
 *
 * <p>Concrete transports are selected by the active deployment profile:
 * SMTP for local development and GreenMail tests, Resend HTTPS for production.
 *
 * <p><b>CodeRabbit round 1 on #840 (the delivery identity):</b> the default
 * method's {@code deliveryKey} names WHAT is being delivered — the
 * notification's own occurrence identity (the conversation+message pair, the
 * booking's event), never a fingerprint of the rendered content. A content
 * fingerprint collapses two DISTINCT occurrences that happen to render the
 * same (two messages of one conversation to one recipient: same subject,
 * same template, same composed text — the second send replays the first's
 * provider idempotency record within its retention window and the second
 * email silently never goes out); a delivery key keeps them apart by
 * construction. The transport MAY fold the key into its idempotency
 * mechanism (Resend: the {@code Idempotency-Key} header) — and a time-varying
 * payload (the password-reset's remaining-minutes) must ride the key's
 * STABLE occurrence identity, not its rendered instant, so the registry's
 * resubmission of the SAME event replays rather than double-sends.</p>
 */
public interface EmailSender {

    void send(String to, String subject, String template, Map<String, Object> variables);

    /**
     * The delivery-identity overload: same send, carrying the notification's
     * occurrence key. Implementations that key provider idempotency on this
     * value MUST use it as given (never re-derive from content); those that
     * cannot transport it simply ignore it. The default delegates so the
     * widening is source-compatible for every existing caller.
     */
    default void send(String to, String subject, String template, Map<String, Object> variables,
                      String deliveryKey) {
        send(to, subject, template, variables);
    }
}
