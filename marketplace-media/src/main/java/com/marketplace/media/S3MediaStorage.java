package com.marketplace.media;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import java.net.URI;
import java.time.Duration;

/**
 * The S3-compatible storage channel — the ONLY class in this module that talks
 * AWS SDK. Official evidence (cached under {@code scripts/media-doc-verify/}):
 * <ul>
 *   <li>R2 presigned-URL doc: "Presigned URLs are generated server-side with no
 *       communication with R2, requiring only your R2 API credentials and an
 *       implementation of the AWS Signature Version 4 signing algorithm" —
 *       presigning is local computation, never a network call.</li>
 *   <li>S3Presigner javadoc (s3-2.54.13-sources.jar): presigned requests are
 *       valid for the configured "signature duration", at most 7 days; browser-
 *       compatible presigned requests need nothing but a host header.</li>
 *   <li>R2 doc region note: {@code region: "auto" // Required by SDK but not
 *       used by R2}.</li>
 * </ul>
 *
 * <p>Content-type pinning: the declared {@code contentType} is part of the
 * signed PutObjectRequest, so a client cannot upload bytes of a different type
 * under the issued URL without breaking the signature (the storage rejects it).
 *
 * <p><b>D.4 (compliance plan wave D) — the channel's isolation seam.</b> The
 * four network operations ({@link #verifyUploaded}, {@link #deleteObject},
 * {@link #getObject}, {@link #putObject}) are the ONLY storage-side surface,
 * and every one of them rides through a Resilience4j {@link CircuitBreaker}
 * created by the auto-configured {@code CircuitBreakerRegistry} (instance
 * {@code mediaStorage}, application.yml). The official programmatic API
 * (the Resilience4j reference's decorator style) is used instead of the
 * annotation style because the precise failure window is the CHANNEL call
 * itself: a DB or validation failure in a calling service method must never
 * count toward the storage circuit, and this final, package-private class is
 * not a proxy candidate anyway. A storage outage then behaves as: verify
 * degrades to the honest "not verifiable" false (the existing anti-forgery
 * contract), the thumbnail pipeline and best-effort deletes fail FAST with
 * {@code CallNotPermittedException} instead of hanging on connect timeouts,
 * and the half-open probes bound the recovery traffic. Presigning stays
 * unwrapped by design — the R2/AWS doc evidence above: it is local
 * computation, never a network call.
 */
final class S3MediaStorage implements AutoCloseable {

    private final S3Presigner presigner;
    private final S3Client client;
    private final String bucket;
    private final Duration presignTtl;
    private final SdkHttpClient httpClient;
    private final CircuitBreaker circuitBreaker;

    /**
     * F-833-1: the AWS S3 {@code DeleteObjects} service documentation's own
     * per-request batch ceiling — "up to 1,000 keys per call" — the hard
     * bound the pre-fix single request exceeded for subjects holding more
     * than a thousand media objects (originals + thumbnails).
     */
    private static final int DELETE_OBJECTS_MAX_KEYS_PER_REQUEST = 1_000;

    /**
     * Production constructor — builds the presigner, the client and its JDK-based
     * HTTP implementation from bound properties. Used by {@code MediaConfig}.
     *
     * @param circuitBreaker the {@code mediaStorage} instance from the
     *                       auto-configured registry (D.4 — see class javadoc)
     */
    S3MediaStorage(MediaProperties.Storage storage, Duration presignTtl, CircuitBreaker circuitBreaker) {
        this.httpClient = UrlConnectionHttpClient.builder().build();
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(storage.accessKey(), storage.secretKey()));
        var region = Region.of(storage.region());
        var endpoint = URI.create(storage.endpoint());
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !storage.allowInsecureEndpoint()) {
            // Cleartext S3 endpoints transmit SigV4 credentials and object bytes
            // unencrypted (CWE-319, CodeRabbit #241). HTTP is an explicit
            // per-deployment opt-in reserved for local emulators.
            throw new IllegalStateException(
                    "marketplace.media.storage.endpoint must use https (got: "
                            + storage.endpoint() + ") — set marketplace.media.storage.allow-insecure-endpoint=true"
                            + " only for a local non-production emulator");
        }
        // Path-style addressing ({endpoint}/{bucket}/{key}) is forced explicitly:
        // the SDK default is virtual-host style (S3Configuration sources,
        // DEFAULT_PATH_STYLE_ACCESS_ENABLED = false), which requires wildcard
        // DNS for the bucket subdomain. Path-style URLs are deterministic and
        // work on every S3-compatible endpoint including R2.
        var addressing = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
        this.presigner = S3Presigner.builder()
                .region(region)
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .serviceConfiguration(addressing)
                .build();
        this.client = S3Client.builder()
                .region(region)
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .httpClient(this.httpClient)
                .serviceConfiguration(addressing)
                .build();
        this.bucket = storage.bucket();
        this.presignTtl = presignTtl;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Test constructor — collaborators injected (real presigner against a fake
     * endpoint still works offline; a mocked client for HeadObject tests; the
     * breaker rides from the test's own registry).
     */
    S3MediaStorage(S3Presigner presigner, S3Client client, String bucket, Duration presignTtl,
                   CircuitBreaker circuitBreaker) {
        this.presigner = presigner;
        this.client = client;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
        this.httpClient = null;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Presigns a single-object PUT for the given key. Pure signing — no network.
     */
    String presignUpload(String objectKey, String contentType) {
        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presign = PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(putObject)
                .build();
        return presigner.presignPutObject(presign).url().toString();
    }

    /**
     * Presigns a single-object GET for the given key. Pure signing — no network.
     */
    String presignDownload(String objectKey) {
        GetObjectRequest getObject = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .getObjectRequest(getObject)
                .build();
        return presigner.presignGetObject(presign).url().toString();
    }

    /**
     * Verifies via HeadObject that the uploaded object exists with exactly the
     * declared content type and size. This is the single network call of the
     * whole upload flow — the anti-forgery gate that stops a client from
     * "confirming" an upload it never performed.
     */
    boolean verifyUploaded(String objectKey, String contentType, long sizeBytes) {
        HeadObjectResponse head;
        try {
            head = circuitBreaker.executeSupplier(() -> client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build()));
        } catch (RuntimeException ex) {
            // NoSuchKey, 403 on missing object, connectivity — all mean "not verifiable"
            // (D.4: the failure is counted by the breaker's window; an OPEN
            // circuit surfaces here as CallNotPermittedException — same honest
            // false, now failing fast instead of hanging on connect timeouts)
            return false;
        }
        return sizeEquals(head, sizeBytes) && contentTypeEquals(head, contentType);
    }

    private static boolean sizeEquals(HeadObjectResponse head, long sizeBytes) {
        return head.contentLength() != null && head.contentLength() == sizeBytes;
    }

    private static boolean contentTypeEquals(HeadObjectResponse head, String contentType) {
        return head.contentType() != null && head.contentType().equalsIgnoreCase(contentType);
    }

    /**
     * Best-effort object removal (owner delete). Storage-side failure is logged
     * by the caller and never fails the API call — bucket lifecycle rules own
     * orphan cleanup.
     */
    void deleteObject(String objectKey) {
        circuitBreaker.executeRunnable(() -> client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build()));
    }

    /**
     * G3 (adversarial review 2026-11-10, owner-gate batch 6a — the erasure
     * flow's storage leg) + F-833-1 (the 2026-11-14 adversarial behavioral
     * review, P2): batch best-effort removal of KNOWN keys — the
     * account-erasure flow's media adapter holds the subject's exact
     * object keys (originals + thumbnails), so the official documented
     * batch API is the right channel: AWS S3 {@code DeleteObjects}
     * (the multi-object delete — <b>up to 1,000 keys per call</b>, the
     * service documentation's own batch shape) instead of one round trip
     * per object. A subject holding MORE than 1,000 keys is split into
     * consecutive ≤1,000-key batches — the pre-fix single request
     * exceeded the service's documented ceiling and was rejected
     * WHOLE, so the tier-2 destruction never happened while the purge
     * logged completion (the review's measured finding). Duplicate keys
     * are deduped first (the service rejects a request holding the same
     * key twice).
     *
     * <p>Best-effort per batch (the {@link #deleteObject} contract's own
     * discipline): a batch's wire failure never aborts the remaining
     * batches — every batch is attempted, and the FIRST failure is
     * re-thrown once the loop ends so the caller's log line (the tier-2
     * gap signal) still fires; the successfully deleted batches
     * stand, and a re-run converges idempotently. A batch whose HTTP
     * call succeeded but whose response carries per-key
     * {@code errors()} entries (the service's own partial-failure
     * surface) is surfaced the same way: the entry count and sample
     * ride the thrown message, the successful keys stand.</p>
     *
     * <p>Same best-effort contract as {@link #deleteObject}:
     * storage-side failure is logged by the caller, never fatal — bucket
     * lifecycle rules own orphans. An empty/null input is a no-op (the
     * subject held no media — the honest zero, never a wire call).</p>
     */
    void deleteObjects(java.util.Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        java.util.List<String> distinctKeys = objectKeys.stream().distinct().toList();
        RuntimeException firstFailure = null;
        for (int start = 0; start < distinctKeys.size(); start += DELETE_OBJECTS_MAX_KEYS_PER_REQUEST) {
            int end = Math.min(start + DELETE_OBJECTS_MAX_KEYS_PER_REQUEST, distinctKeys.size());
            java.util.List<String> batchKeys = distinctKeys.subList(start, end);
            java.util.List<software.amazon.awssdk.services.s3.model.ObjectIdentifier> identifiers =
                    batchKeys.stream()
                            .map(key -> software.amazon.awssdk.services.s3.model.ObjectIdentifier.builder()
                                    .key(key)
                                    .build())
                            .toList();
            try {
                DeleteObjectsResponse response = circuitBreaker.executeSupplier(() -> client.deleteObjects(
                        DeleteObjectsRequest.builder()
                                .bucket(bucket)
                                .delete(d -> d.objects(identifiers))
                                .build()));
                if (response != null && !response.errors().isEmpty()) {
                    throw new IllegalStateException("S3 reported " + response.errors().size()
                            + " per-key delete failures (first: key='" + response.errors().get(0).key()
                            + "', code='" + response.errors().get(0).code()
                            + "', message='" + response.errors().get(0).message() + "')");
                }
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * L28: fetches the raw object bytes — the thumbnail pipeline's read half
     * (scale the original server-side). The AWS SDK v2 synchronous channel
     * the client already uses ({@code getObjectAsBytes}).
     */
    byte[] getObject(String objectKey) {
        ResponseBytes<GetObjectResponse> object =
                circuitBreaker.executeSupplier(() -> client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build()));
        return object.asByteArray();
    }

    /**
     * L28: stores the generated thumbnail bytes under the deterministic
     * {@code {objectKey}/thumb} key — the pipeline's write half. Server-side
     * PUT via the SDK client (not a presign: the thumbnail is produced by
     * this process, never by a client).
     */
    void putObject(String objectKey, String contentType, byte[] bytes) {
        circuitBreaker.executeRunnable(() -> client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes)));
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
        if (httpClient != null) {
            httpClient.close();
        }
    }
}
