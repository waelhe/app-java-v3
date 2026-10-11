package com.marketplace.notifications.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 7 (plan D-10, ADR-0003): the FCM HTTP v1 channel through Google's
 * OWN Admin SDK — the parent POM's managed {@code firebase-admin} (the
 * house's recorded "official client" decision; no Spring-native
 * equivalent exists). Enabled ONLY when the service-account JSON is in
 * the environment ({@code marketplace.push.fcm.credentials-json}) — the
 * keys-outside-the-code rule, the secrets are the ops layer's own record.
 *
 * <p>{@code sendEachForMulticast} is the official batching API (500
 * tokens per call; the house cap on one user's registry is far below it,
 * so one call per dispatch). The per-response verdict rides back to the
 * registry: {@code UNREGISTERED}/{@code INVALID_ARGUMENT} prune their
 * token (the device uninstalled, the token rotated), everything else is
 * a logged provider failure the retry policy of the channel owns.
 */
@Component
@Conditional(PushChannelConfiguredCondition.class)
public class FcmHttpV1PushAdapter implements PushNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(FcmHttpV1PushAdapter.class);

    private final FirebaseMessaging messaging;

    public FcmHttpV1PushAdapter(
            @Value("${marketplace.push.fcm.credentials-json}") String credentialsJson) {
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(
                        new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))))
                .build();
        // The official idempotent bootstrap: one app per process, the
        // [DEFAULT] name is the SDK's own convention.
        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options);
        }
        this.messaging = FirebaseMessaging.getInstance(FirebaseApp.getInstance());
        log.info("FCM HTTP v1 push channel bound (the official firebase-admin client)");
    }

    @Override
    public List<PushResult> send(List<String> tokens, String title, String body) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        try {
            BatchResponse response = messaging.sendEachForMulticast(MulticastMessage.builder()
                    .addAllTokens(tokens)
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .build());
            List<PushResult> results = new ArrayList<>(tokens.size());
            int i = 0;
            for (SendResponse sendResponse : response.getResponses()) {
                String token = tokens.get(i++);
                if (sendResponse.isSuccessful()) {
                    results.add(new PushResult(token, true, false));
                    continue;
                }
                FirebaseMessagingException cause = sendResponse.getException();
                MessagingErrorCode code = cause == null ? null : cause.getMessagingErrorCode();
                boolean unregistered = code == MessagingErrorCode.UNREGISTERED
                        || code == MessagingErrorCode.INVALID_ARGUMENT;
                results.add(new PushResult(token, false, unregistered));
                log.warn("FCM send rejected token (code={}): {}", code, cause == null ? "unknown" : cause.getMessage());
            }
            return results;
        } catch (FirebaseMessagingException e) {
            // The whole-batch failure (auth, quota): every token reports the
            // failure, nothing is pruned — the registry only prunes the
            // provider's own per-token verdicts.
            log.error("FCM batch send failed: {}", e.getMessage());
            return tokens.stream().map(t -> new PushResult(t, false, false)).toList();
        }
    }
}
