package com.marketplace.shared.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The mail channel's sender identity — the one setting the channel can never
 * run without again.
 *
 * <p><b>Why this record exists (the measured incident it closes — watchdog
 * incident #682, 2026-10-20):</b> the channel shipped with no From anywhere —
 * no {@code helper.setFrom(...)} in {@link EmailService}, no
 * {@code spring.mail.from} (Boot's {@code MailProperties} does not even own
 * such a key). JavaMail therefore derived the envelope sender from
 * {@code user.name@hostname}, which in the production container is not a
 * valid address, and the provider rejected every submission at the MAIL FROM
 * stage with {@code 550 Invalid `from` field} — a raw-SMTP dialogue replay
 * (aborted before DATA, zero mail sent) isolated the variable with surgical
 * precision: the same credentials ACCEPT a valid sender and ACCEPT the very
 * recipient the incident carried; only the missing sender is rejected. The
 * mail leg of every {@code @ApplicationModuleListener} that rides it could
 * never complete, the Modulith publication registry kept the events FAILED,
 * and the event-bus health indicator honestly reported the platform DOWN
 * while liveness/readiness stayed UP — the exact telemetry gap the S10
 * postmortem (docs/observability/postmortems/
 * 2026-09-26-s10-aggregate-health-blind.md) documented from the other
 * direction. The verification mail for one E2E-provisioned account was the
 * first publication to surface it.</p>
 *
 * <p><b>The official design (explicit, not derived):</b> the Framework
 * reference the service already cites
 * (docs.spring.io/spring-framework/reference/integration/email.html) sets the
 * sender on the message through {@code MimeMessageHelper.setFrom(...)} — the
 * one deterministic, measured path. The alternative — leaning on the
 * JavaMail session fallback chain ({@code mail.from} /
 * {@code mail.smtp.from} via {@code InternetAddress.getLocalAddress}) — is
 * implicit derivation by another name: exactly the shape that produced this
 * incident. The house rules decide the rest: INV-2 (explicit complete
 * settings) and D6 (production keys fail fast) — the record has NO default,
 * and because it is registered only when the mail channel is active (see
 * {@link EmailServiceAutoConfiguration}), an activated channel without a
 * sender fails STARTUP instead of silently mailing from a derived garbage
 * address. Profiles that legitimately run the channel carry their own value:
 * production and staging bind {@code ${MAIL_FROM}} with no default, the dev
 * and test profiles carry local defaults, and the platform's operational
 * value rides the Railway variable {@code MAIL_FROM} (the documented
 * runbook's variable-naming convention).</p>
 *
 * <p><b>Accepted shapes:</b> both forms the provider documents are valid —
 * {@code email@example.com} and {@code Name <email@example.com>} — parsed
 * here with the official {@link InternetAddress#parse(String, boolean)} in
 * strict mode, and an address without a routable domain is rejected at
 * binding time so the failure reads as configuration, not as a bounced
 * submission at runtime.</p>
 */
@ConfigurationProperties(prefix = "marketplace.mail")
public record MailChannelProperties(String from) {

    public MailChannelProperties {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException(
                    "marketplace.mail.from is required when the mail channel is active "
                            + "(spring.mail.host set): the sender identity must be configured "
                            + "explicitly — e.g. MAIL_FROM='Marketplace <no-reply@example.com>' "
                            + "in the deployment environment. A channel without a sender is the "
                            + "measured root of watchdog incident #682: every submission is "
                            + "rejected at the MAIL FROM stage.");
        }
        try {
            InternetAddress[] parsed = InternetAddress.parse(from, true);
            boolean routable = parsed.length > 0
                    && parsed[0].getAddress() != null
                    && parsed[0].getAddress().contains("@");
            if (!routable) {
                throw new AddressException("no routable address", from);
            }
        } catch (AddressException ex) {
            throw new IllegalArgumentException(
                    "marketplace.mail.from '" + from + "' is not a valid sender — use "
                            + "'email@example.com' or 'Name <email@example.com>' "
                            + "(jakarta.mail InternetAddress strict parse: " + ex.getMessage() + ")",
                    ex);
        }
    }
}
