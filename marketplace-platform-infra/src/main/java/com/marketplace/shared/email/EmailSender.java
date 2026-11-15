package com.marketplace.shared.email;

import java.util.Map;

/**
 * Transport-neutral transactional email contract.
 *
 * <p>Concrete transports are selected by the active deployment profile:
 * SMTP for local development and GreenMail tests, Resend HTTPS for production.
 */
public interface EmailSender {

    void send(String to, String subject, String template, Map<String, Object> variables);
}
