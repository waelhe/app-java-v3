package com.marketplace.media;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the S3-compatible channel with a REAL presigner against a fake
 * endpoint — official R2 documentation: "Presigned URLs are generated
 * server-side with no communication with R2", so signing is pure local
 * computation and needs no network. The HeadObject verification path uses a
 * mocked client.
 */
@ExtendWith(MockitoExtension.class)
class S3MediaStorageTest {

    private static final String ENDPOINT = "https://storage.example.local";
    private static final String BUCKET = "media-bucket";

    private S3Presigner realPresigner() {
        return S3Presigner.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create(ENDPOINT))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access", "test-secret")))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    /**
     * D.4: a defaults-registry breaker (100-call window, 50% threshold) — it
     * can never open inside these signing/verification tests, so the storage's
     * new isolation seam rides fully inert exactly as it does in the passing
     * paths of production. The OPEN-state behavior has its own dedicated
     * {@code S3MediaStorageIsolationTest}.
     */
    private static CircuitBreaker neverOpens() {
        return CircuitBreakerRegistry.ofDefaults().circuitBreaker("mediaStorage");
    }

    @Mock
    private S3Client client;

    @Test
    void presignUpload_signsKeyContentTypeAndExpiry() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());
            String url = storage.presignUpload("listings/abc/photo.jpg", "image/jpeg");

            assertTrue(url.startsWith(ENDPOINT + "/" + BUCKET + "/listings/abc/photo.jpg"),
                    "URL must address the exact object: " + url);
            assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), "SigV4 must be used");
            assertTrue(url.contains("X-Amz-SignedHeaders="), "signed headers must be present");
            assertTrue(url.contains("X-Amz-Expires=900"), "15 minutes = 900 seconds");
            assertTrue(url.contains("X-Amz-Signature="), "URL carries the signature");
        }
    }

    @Test
    void presignDownload_addressesSameObjectForGet() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());
            String url = storage.presignDownload("listings/abc/photo.jpg");

            assertTrue(url.startsWith(ENDPOINT + "/" + BUCKET + "/listings/abc/photo.jpg"));
            assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
        }
    }

    @Test
    void verifyUploaded_trueWhenTypeAndSizeMatch() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());
            when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder()
                            .contentLength(2048L)
                            .contentType("image/jpeg")
                            .build());

            assertTrue(storage.verifyUploaded("k", "image/jpeg", 2048L));
        }
    }

    @Test
    void verifyUploaded_falseWhenSizeMismatches() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());
            when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder()
                            .contentLength(999L)
                            .contentType("image/jpeg")
                            .build());

            assertFalse(storage.verifyUploaded("k", "image/jpeg", 2048L));
        }
    }

    @Test
    void verifyUploaded_falseWhenTypeMismatches() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());
            when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder()
                            .contentLength(2048L)
                            .contentType("application/octet-stream")
                            .build());

            assertFalse(storage.verifyUploaded("k", "image/jpeg", 2048L));
        }
    }

    @Test
    void verifyUploaded_falseWhenObjectMissingOrUnreachable() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());
            when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                    .thenThrow(new RuntimeException("NoSuchKey"));

            assertFalse(storage.verifyUploaded("k", "image/jpeg", 2048L));
        }
    }

    @Test
    void deleteObject_delegatesToTheClient() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());

            storage.deleteObject("listings/abc/photo.jpg");

            verify(client).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
        }
    }

    @Test
    void g3_deleteObjects_sendsTheExactKnownKeysInOneBatchCall() {
        // G3 (owner-gate batch 6a — the erasure flow's storage leg): the
        // documented batch API carries the purge's KNOWN keys in ONE wire
        // call — the request holds exactly the collected originals and
        // thumbnails, nothing else.
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());

            storage.deleteObjects(java.util.List.of("listings/1/a.jpg", "listings/1/a.jpg/thumb",
                    "posts/2/b.jpg", "review-media/3/c.jpg"));

            var captor = org.mockito.ArgumentCaptor.forClass(
                    software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class);
            verify(client).deleteObjects(captor.capture());
            var request = captor.getValue();
            org.assertj.core.api.Assertions.assertThat(request.bucket()).isEqualTo(BUCKET);
            org.assertj.core.api.Assertions.assertThat(request.delete().objects())
                    .extracting(software.amazon.awssdk.services.s3.model.ObjectIdentifier::key)
                    .containsExactlyInAnyOrder("listings/1/a.jpg", "listings/1/a.jpg/thumb",
                            "posts/2/b.jpg", "review-media/3/c.jpg");
        }
    }

    @Test
    void g3_deleteObjects_emptyOrNullInputIsTheHonestNoOp() {
        // A subject with no media (or an already-purged re-run) never issues
        // a wire call.
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());

            storage.deleteObjects(java.util.List.of());
            storage.deleteObjects(null);

            verify(client, org.mockito.Mockito.never())
                    .deleteObjects(any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class));
        }
    }

    // ---- F-833-1 (the 2026-11-14 adversarial behavioral review, P2) ----

    @Test
    void f833_1_moreThanAThousandKeysSplitsIntoBoundedBatches() {
        // The service's documented ceiling is 1,000 keys per DeleteObjects
        // call — the pre-fix single request exceeded it and was rejected
        // WHOLE, so a subject holding 1,500 media objects never lost a
        // single byte in the tier-2 destruction while the purge logged
        // completion. The fix splits into consecutive bounded batches.
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());

            java.util.List<String> keys = new java.util.ArrayList<>(1_500);
            for (int i = 0; i < 1_500; i++) {
                keys.add("listings/" + i + "/photo.jpg");
            }
            storage.deleteObjects(keys);

            var captor = org.mockito.ArgumentCaptor.forClass(
                    software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class);
            verify(client, org.mockito.Mockito.times(2)).deleteObjects(captor.capture());
            var requests = captor.getAllValues();
            org.assertj.core.api.Assertions.assertThat(requests)
                    .as("1,500 keys split into exactly two bounded batches")
                    .hasSize(2);
            org.assertj.core.api.Assertions.assertThat(requests.get(0).delete().objects())
                    .as("the first batch carries the service ceiling exactly")
                    .hasSize(1_000);
            org.assertj.core.api.Assertions.assertThat(requests.get(1).delete().objects())
                    .as("the second batch carries the remainder")
                    .hasSize(500);
            org.assertj.core.api.Assertions.assertThat(requests.get(0).delete().objects().get(0).key())
                    .as("the batch order preserves the collected order")
                    .isEqualTo("listings/0/photo.jpg");
            org.assertj.core.api.Assertions.assertThat(requests.get(1).delete().objects().get(499).key())
                    .isEqualTo("listings/1499/photo.jpg");
        }
    }

    @Test
    void f833_1_exactlyTheCeilingStaysOneBatchAndDuplicatesAreDeduped() {
        // Exactly 1,000 keys ride ONE call (the ceiling is inclusive), and a
        // key collected twice (an original re-listed under the same object
        // key) is deduped first — the service rejects a request holding the
        // same key twice.
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());

            java.util.List<String> thousand = new java.util.ArrayList<>(1_000);
            for (int i = 0; i < 999; i++) {
                thousand.add("posts/" + i + "/img.jpg");
            }
            thousand.add("posts/0/img.jpg"); // a duplicate of the first
            storage.deleteObjects(thousand);

            verify(client, org.mockito.Mockito.times(1))
                    .deleteObjects(any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class));
            var captor = org.mockito.ArgumentCaptor.forClass(
                    software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class);
            verify(client).deleteObjects(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().delete().objects())
                    .as("the duplicate never reaches the wire")
                    .hasSize(999);
        }
    }

    @Test
    void f833_1_perKeyPartialFailuresSurfaceToTheCallersLogLine() {
        // The service's own partial-failure surface: a 200-response whose
        // body carries per-key errors. The pre-fix code discarded the
        // response entirely — the gap stayed invisible. The fix surfaces
        // the count and sample through the thrown message so the caller's
        // log line fires (the tier-2 gap signal).
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());

            org.mockito.Mockito.when(client.deleteObjects(
                            any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class)))
                    .thenReturn(software.amazon.awssdk.services.s3.model.DeleteObjectsResponse.builder()
                            .errors(java.util.List.of(software.amazon.awssdk.services.s3.model.S3Error.builder()
                                    .key("listings/1/a.jpg").code("AccessDenied").message("denied").build()))
                            .build());

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            storage.deleteObjects(java.util.List.of("listings/1/a.jpg")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1 per-key delete failures")
                    .hasMessageContaining("listings/1/a.jpg")
                    .hasMessageContaining("AccessDenied");
        }
    }

    @Test
    void f833_1_aFailedBatchNeverAbortsTheRemainingBatches() {
        // Best-effort per batch: the first batch's wire failure must not
        // cost the second batch its deletion attempt — every batch is
        // attempted, and the FIRST failure is re-thrown once the loop ends
        // (the caller's log still fires; a re-run converges idempotently).
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());

            java.util.List<String> keys = new java.util.ArrayList<>(1_500);
            for (int i = 0; i < 1_500; i++) {
                keys.add("review-media/" + i + "/c.jpg");
            }
            org.mockito.Mockito.when(client.deleteObjects(
                            any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class)))
                    .thenThrow(new RuntimeException("first batch wire failure"))
                    .thenReturn(software.amazon.awssdk.services.s3.model.DeleteObjectsResponse.builder().build());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.deleteObjects(keys))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("first batch wire failure");

            verify(client, org.mockito.Mockito.times(2))
                    .deleteObjects(any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class));
        }
    }

    @Test
    void close_shutsDownPresignerAndClient() {
        S3Presigner presigner = org.mockito.Mockito.mock(S3Presigner.class);
        S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), neverOpens());

        storage.close();

        verify(presigner).close();
        verify(client).close();
    }

    private static MediaProperties.Storage storage(String endpoint, boolean allowInsecure) {
        return new MediaProperties.Storage(endpoint, "auto", BUCKET, "test-access", "test-secret", allowInsecure);
    }

    @Test
    void productionConstructor_rejectsCleartextEndpoint() {
        // CWE-319 / CodeRabbit #241: an http:// endpoint carries SigV4
        // credentials and object bytes in the clear — the production
        // constructor must refuse it before any client or presigner is built.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new S3MediaStorage(storage("http://localhost:4566", false), Duration.ofMinutes(15), neverOpens()));

        assertTrue(thrown.getMessage().contains("must use https"));
        assertTrue(thrown.getMessage().contains("http://localhost:4566"));
    }

    @Test
    void productionConstructor_acceptsHttpsEndpoint() {
        // The honest default: https endpoints build normally.
        try (S3MediaStorage storage = new S3MediaStorage(storage("https://media.example.local", false),
                Duration.ofMinutes(15), neverOpens())) {
            assertNotNull(storage);
        }
    }

    @Test
    void productionConstructor_allowsCleartextOnlyWithExplicitOptIn() {
        // The explicit local-emulator escape hatch (allow-insecure-endpoint)
        // is the only way an http endpoint builds.
        try (S3MediaStorage storage = new S3MediaStorage(storage("http://localhost:4566", true),
                Duration.ofMinutes(15), neverOpens())) {
            assertNotNull(storage);
        }
    }
}
