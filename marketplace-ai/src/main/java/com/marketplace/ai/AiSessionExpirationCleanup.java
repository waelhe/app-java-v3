package com.marketplace.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.SessionService;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * Invokes the expiration cleanup in the way documented by Spring AI Session.
 * SessionService intentionally does not schedule TTL cleanup itself.
 */
public class AiSessionExpirationCleanup {

    private static final Logger log = LoggerFactory.getLogger(AiSessionExpirationCleanup.class);

    private final SessionService sessionService;

    public AiSessionExpirationCleanup(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Scheduled(fixedRateString = "${spring.ai.session.expiration.cleanup-interval-ms:3600000}")
    public void deleteExpiredSessions() {
        int deleted = sessionService.deleteExpiredSessions(Instant.now());
        if (deleted > 0) {
            log.info("Spring AI Session expiry sweep deleted {} expired sessions", deleted);
        }
    }
}
