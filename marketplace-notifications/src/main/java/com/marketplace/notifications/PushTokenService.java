package com.marketplace.notifications;

import com.marketplace.notifications.push.PushNotificationPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Stage 7 (plan D-10, ADR-0003): the device-token registry's writes and
 * the push dispatch's routing half — the token lifecycle is the /me
 * family (identity from the authentication itself), the dispatch is the
 * preference gate + the token lookup + the provider's per-token verdicts
 * (the unregistered ones are pruned — the self-healing registry).
 */
@Service
public class PushTokenService {

    private static final Logger log = LoggerFactory.getLogger(PushTokenService.class);

    private final PushTokenRepository pushTokenRepository;
    private final PushNotificationPort pushNotificationPort;
    private final CurrentUserProvider currentUserProvider;

    public PushTokenService(PushTokenRepository pushTokenRepository,
                            PushNotificationPort pushNotificationPort,
                            CurrentUserProvider currentUserProvider) {
        this.pushTokenRepository = pushTokenRepository;
        this.pushNotificationPort = pushNotificationPort;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Registers (or idempotently rebinds) the caller's device token —
     * the UNIQUE token key collapses the repeated register calls into
     * the one row (the reinstall path rebinds user + platform).
     */
    @Transactional
    public PushToken register(UUID userId, String token, PushToken.Platform platform) {
        return pushTokenRepository.findByToken(token)
                .map(existing -> {
                    existing.rebindTo(userId, platform);
                    return pushTokenRepository.save(existing);
                })
                .orElseGet(() -> pushTokenRepository.save(PushToken.register(userId, token, platform)));
    }

    /** The caller's own unregister — a token is only ever removed by its owner. */
    @Transactional
    public void unregister(UUID userId, String token) {
        pushTokenRepository.deleteByUserIdAndToken(userId, token);
    }

    /**
     * The dispatch half: the preference gate + the token lookup + the
     * provider's verdicts (the unregistered tokens leave the registry).
     *
     * @return whether any token accepted the message
     */
    @Transactional
    public boolean sendToUser(UUID userId, NotificationType type, String title, String body) {
        List<PushToken> tokens = pushTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            return false;
        }
        List<String> addresses = tokens.stream().map(PushToken::getToken).toList();
        List<PushNotificationPort.PushResult> results = pushNotificationPort.send(addresses, title, body);
        results.stream().filter(PushNotificationPort.PushResult::unregistered)
                .forEach(dead -> {
                    pushTokenRepository.findByToken(dead.token()).ifPresent(pushTokenRepository::delete);
                    log.info("Pruned dead push token for user {} (the provider reports it unregistered)",
                            userId);
                });
        return results.stream().anyMatch(PushNotificationPort.PushResult::success);
    }

    /** The /me identity resolution for the controller (the family's shape). */
    public PushToken registerForCaller(String token, PushToken.Platform platform,
                                       Authentication authentication) {
        return register(currentUserProvider.getCurrentUserId(authentication), token, platform);
    }

    public void unregisterForCaller(String token, Authentication authentication) {
        unregister(currentUserProvider.getCurrentUserId(authentication), token);
    }
}
