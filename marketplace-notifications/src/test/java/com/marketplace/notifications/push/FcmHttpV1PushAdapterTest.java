package com.marketplace.notifications.push;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Stage 7 (ADR-0003) — the FCM channel's per-token verdict law, driven
 * through the official firebase-admin surface: the batch response's
 * per-send verdict rides back verbatim (success / the pruning classes
 * UNREGISTERED and INVALID_ARGUMENT / every other code a logged provider
 * failure), a whole-batch failure prunes nothing, and an empty registry
 * never reaches the provider. The messaging instance is intercepted at
 * the SDK's static seam; the credentials parse a synthetic service
 * account (a throwaway RSA key — the keys-outside-the-code rule keeps
 * real ones out of the tree).
 */
class FcmHttpV1PushAdapterTest {

    private MockedStatic<FirebaseMessaging> messagingStatic;
    private FirebaseMessaging messaging;
    private FcmHttpV1PushAdapter adapter;

    @AfterEach
    void teardown() {
        if (messagingStatic != null) {
            messagingStatic.close();
        }
        FirebaseApp.getApps().forEach(FirebaseApp::delete);
    }

    private void boundAdapter() throws Exception {
        messaging = mock(FirebaseMessaging.class);
        messagingStatic = mockStatic(FirebaseMessaging.class);
        messagingStatic.when(() -> FirebaseMessaging.getInstance(any(FirebaseApp.class)))
                .thenReturn(messaging);
        adapter = new FcmHttpV1PushAdapter(serviceAccountJson());
    }

    /** A throwaway service account — parseable, never a network caller. */
    private static String serviceAccountJson() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        // Single-line base64 — the JSON string carries the PEM as escaped
        // \n sequences (raw newlines inside a JSON string are illegal).
        String pkcs8 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        return """
                {"type":"service_account","project_id":"test-project","private_key_id":"kid",
                 "private_key":"-----BEGIN PRIVATE KEY-----\\n%s\\n-----END PRIVATE KEY-----\\n",
                 "client_email":"push@test-project.iam.gserviceaccount.com","client_id":"123",
                 "token_uri":"https://oauth2.googleapis.com/token"}""".formatted(pkcs8);
    }

    private SendResponse successfulResponse() {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(true);
        return response;
    }

    private SendResponse failingResponse(MessagingErrorCode code) throws FirebaseMessagingException {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(false);
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(code);
        when(response.getException()).thenReturn(exception);
        return response;
    }

    @Test
    void anEmptyRegistryNeverReachesTheProvider() throws Exception {
        boundAdapter();

        assertThat(adapter.send(List.of(), "title", "body")).isEmpty();
        verifyNoInteractions(messaging);
    }

    @Test
    void theProviderVerdictsRideBackPerToken() throws Exception {
        boundAdapter();
        // The verdict list builds BEFORE the batch stub registers it —
        // stubbing another mock inside an unfinished when() is the
        // UnfinishedStubbingException the detector exists for.
        List<SendResponse> verdicts = List.of(
                successfulResponse(),
                failingResponse(MessagingErrorCode.UNREGISTERED),
                failingResponse(MessagingErrorCode.INVALID_ARGUMENT),
                failingResponse(MessagingErrorCode.INTERNAL));
        BatchResponse batch = mock(BatchResponse.class);
        when(messaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);
        when(batch.getResponses()).thenReturn(verdicts);

        List<PushNotificationPort.PushResult> results = adapter.send(
                List.of("t-ok", "t-dead", "t-malformed", "t-transient"), "title", "body");

        assertThat(results).hasSize(4);
        assertThat(results.get(0).token()).isEqualTo("t-ok");
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).unregistered()).isFalse();
        assertThat(results.get(1).token()).isEqualTo("t-dead");
        assertThat(results.get(1).success()).isFalse();
        assertThat(results.get(1).unregistered()).isTrue();
        assertThat(results.get(2).token()).isEqualTo("t-malformed");
        assertThat(results.get(2).success()).isFalse();
        assertThat(results.get(2).unregistered()).isTrue();
        assertThat(results.get(3).token()).isEqualTo("t-transient");
        assertThat(results.get(3).success()).isFalse();
        assertThat(results.get(3).unregistered()).isFalse();
    }

    @Test
    void aWholeBatchFailurePrunesNothing() throws Exception {
        boundAdapter();
        FirebaseMessagingException batchFailure = mock(FirebaseMessagingException.class);
        when(messaging.sendEachForMulticast(any(MulticastMessage.class))).thenThrow(batchFailure);

        List<PushNotificationPort.PushResult> results = adapter.send(
                List.of("t-a", "t-b"), "title", "body");

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.unregistered()).isFalse();
        });
    }
}
