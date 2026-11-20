package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

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
    /** R2 currently has no S3 bucket-versioning or ListObjectVersions API. */
    private final boolean unversionedR2Endpoint;

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
        this.unversionedR2Endpoint = isCloudflareR2Endpoint(storage.endpoint());
    }

    /**
     * Test constructor — collaborators injected (real presigner against a fake
     * endpoint still works offline; a mocked client for HeadObject tests; the
     * breaker rides from the test's own registry).
     */
    S3MediaStorage(S3Presigner presigner, S3Client client, String bucket, Duration presignTtl,
                   CircuitBreaker circuitBreaker) {
        this(presigner, client, bucket, presignTtl, circuitBreaker, false);
    }

    S3MediaStorage(S3Presigner presigner, S3Client client, String bucket, Duration presignTtl,
                   CircuitBreaker circuitBreaker, boolean unversionedR2Endpoint) {
        this.presigner = presigner;
        this.client = client;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
        this.httpClient = null;
        this.circuitBreaker = circuitBreaker;
        this.unversionedR2Endpoint = unversionedR2Endpoint;
    }

    private static boolean isCloudflareR2Endpoint(String endpoint) {
        try {
            String host = URI.create(endpoint).getHost();
            return host != null && host.toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".r2.cloudflarestorage.com");
        } catch (RuntimeException ex) {
            return false;
        }
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
     * Promotes a verified temporary upload to its immutable published key.
     * CopyObject is supported by both Amazon S3 and Cloudflare R2's current
     * S3-compatibility contract. The caller holds the media-row write lock so
     * account erasure cannot commit between the verification and promotion.
     */
    /**
     * Copies a staged object only if the source is still the same object whose
     * metadata was just checked. The conditional source ETag closes the window
     * in which a reusable presigned PUT could replace the key between HEAD and
     * CopyObject. AWS S3 and Cloudflare R2 both document x-amz-copy-source-if-match.
     */
    void copyVerifiedObject(String sourceKey, String destinationKey, String contentType, long sizeBytes) {
        HeadObjectResponse head;
        try {
            head = circuitBreaker.executeSupplier(() -> client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(sourceKey)
                    .build()));
        } catch (RuntimeException ex) {
            throw new BadRequestException("Staged upload is no longer available for confirmation");
        }
        String sourceEtag = head == null ? null : head.eTag();
        if (head == null || !sizeEquals(head, sizeBytes) || !contentTypeEquals(head, contentType)
                || sourceEtag == null || sourceEtag.isBlank()) {
            throw new BadRequestException("Staged upload changed before it could be published");
        }
        try {
            circuitBreaker.executeRunnable(() -> client.copyObject(CopyObjectRequest.builder()
                    .bucket(bucket)
                    .key(destinationKey)
                    .copySource(bucket + "/" + sourceKey)
                    .copySourceIfMatch(sourceEtag)
                    .metadataDirective(MetadataDirective.COPY)
                    .build()));
        } catch (S3Exception ex) {
            if (ex.statusCode() == 412) {
                throw new BadRequestException("Staged upload changed during confirmation; please upload again");
            }
            throw ex;
        }
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
     * G3: permanently removes known media objects.
     *
     * <p>Cloudflare R2's current S3 compatibility matrix does not implement
     * GetBucketVersioning or ListObjectVersions and does not support object
     * versioning/Object Lock. Its documented bucket model is therefore handled
     * as unversioned: DeleteObjects by key permanently removes the object.</p>
     *
     * <p>For other S3 endpoints, versioning state is read first. ENABLED and
     * SUSPENDED both require enumerating every object version AND delete marker,
     * then deleting each entry with its explicit versionId. A missing status is
     * the S3 unversioned state. Failure to read versioning or enumerate versions
     * is fatal: the caller retains PURGE_PENDING and retries; it never falls
     * back to a key-only delete when capability is unknown.</p>
     *
     * <p>DeleteObjects accepts at most 1,000 identifiers. Per-key errors in a
     * successful HTTP response are retried for those failed identifiers only;
     * an ambiguous request-level failure retries that request batch. Residual
     * failures propagate after three attempts, leaving the database tombstone
     * retryable. Object Lock is not bypassed.</p>
     */
    void deleteObjects(java.util.Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        java.util.List<String> keys = objectKeys.stream()
                .filter(java.util.Objects::nonNull)
                .filter(key -> !key.isBlank())
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            return;
        }

        if (unversionedR2Endpoint) {
            deleteIdentifierBatches(keys.stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList());
            return;
        }

        GetBucketVersioningResponse versioning = circuitBreaker.executeSupplier(() ->
                client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()));
        if (versioning == null) {
            throw new IllegalStateException("S3 returned no bucket-versioning response; refusing key-only erasure");
        }
        BucketVersioningStatus status = versioning.status();
        if (status == null) {
            // AWS omits Status for a bucket that has never had versioning enabled.
            deleteIdentifierBatches(keys.stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList());
            return;
        }
        if (status != BucketVersioningStatus.ENABLED && status != BucketVersioningStatus.SUSPENDED) {
            // UNKNOWN_TO_SDK_VERSION (or any future enum member) is not proof
            // that the bucket is unversioned. A key-only delete could leave
            // historical versions retrievable, so fail closed.
            throw new IllegalStateException(
                    "Unsupported S3 bucket versioning status '" + status + "'; refusing key-only erasure");
        }

        // Finish enumerating one key before issuing deletes for its versions.
        // This avoids shifting that key's pagination while still batching
        // identifiers across keys up to the service's 1,000-item ceiling.
        java.util.List<ObjectIdentifier> pendingBatch = new java.util.ArrayList<>(
                DELETE_OBJECTS_MAX_KEYS_PER_REQUEST);
        for (String key : keys) {
            java.util.List<ObjectIdentifier> versions = listAllVersionsAndMarkers(key);
            for (ObjectIdentifier identifier : versions) {
                pendingBatch.add(identifier);
                if (pendingBatch.size() == DELETE_OBJECTS_MAX_KEYS_PER_REQUEST) {
                    deleteIdentifierBatches(pendingBatch);
                    pendingBatch.clear();
                }
            }
        }
        if (!pendingBatch.isEmpty()) {
            deleteIdentifierBatches(pendingBatch);
        }
    }

    private java.util.List<ObjectIdentifier> listAllVersionsAndMarkers(String exactKey) {
        java.util.List<ObjectIdentifier> identifiers = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String keyMarker = null;
        String versionIdMarker = null;

        while (true) {
            ListObjectVersionsRequest.Builder request = ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(exactKey);
            if (keyMarker != null) {
                request.keyMarker(keyMarker);
            }
            if (versionIdMarker != null) {
                request.versionIdMarker(versionIdMarker);
            }

            ListObjectVersionsResponse page = circuitBreaker.executeSupplier(
                    () -> client.listObjectVersions(request.build()));
            if (page == null) {
                throw new IllegalStateException("S3 returned no version-list response for key " + exactKey);
            }

            if (page.versions() != null) {
                for (var version : page.versions()) {
                    if (exactKey.equals(version.key())) {
                        addVersionIdentifier(identifiers, seen, exactKey, version.versionId());
                    }
                }
            }
            if (page.deleteMarkers() != null) {
                for (var marker : page.deleteMarkers()) {
                    if (exactKey.equals(marker.key())) {
                        addVersionIdentifier(identifiers, seen, exactKey, marker.versionId());
                    }
                }
            }

            // AWS SDK models represent an omitted optional Boolean as null;
            // only TRUE means another page is contractually present.
            if (!Boolean.TRUE.equals(page.isTruncated())) {
                return identifiers;
            }

            String nextKeyMarker = page.nextKeyMarker();
            String nextVersionIdMarker = page.nextVersionIdMarker();
            if ((nextKeyMarker == null && nextVersionIdMarker == null)
                    || (java.util.Objects.equals(keyMarker, nextKeyMarker)
                    && java.util.Objects.equals(versionIdMarker, nextVersionIdMarker))) {
                throw new IllegalStateException(
                        "S3 version listing did not advance its pagination markers for key " + exactKey);
            }
            keyMarker = nextKeyMarker;
            versionIdMarker = nextVersionIdMarker;
        }
    }

    private static void addVersionIdentifier(java.util.List<ObjectIdentifier> identifiers,
                                             java.util.Set<String> seen,
                                             String key,
                                             String versionId) {
        // The literal string "null" is a valid S3 null-version identifier.
        // A Java null is not: omitting it would turn a permanent delete into
        // a key-only delete marker operation.
        if (versionId == null) {
            throw new IllegalStateException(
                    "S3 omitted versionId while listing a versioned object: " + key);
        }
        String identity = key + "\u0000" + versionId;
        if (seen.add(identity)) {
            identifiers.add(ObjectIdentifier.builder().key(key).versionId(versionId).build());
        }
    }

    private void deleteIdentifierBatches(java.util.List<ObjectIdentifier> identifiers) {
        RuntimeException firstFailure = null;
        for (int start = 0; start < identifiers.size(); start += DELETE_OBJECTS_MAX_KEYS_PER_REQUEST) {
            int end = Math.min(start + DELETE_OBJECTS_MAX_KEYS_PER_REQUEST, identifiers.size());
            java.util.List<ObjectIdentifier> originalBatch = java.util.List.copyOf(identifiers.subList(start, end));
            try {
                deleteIdentifierBatch(originalBatch);
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
                // Continue independent batches. The caller keeps PURGE_PENDING
                // until the entire operation returns successfully.
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void deleteIdentifierBatch(java.util.List<ObjectIdentifier> originalBatch) {
        java.util.List<ObjectIdentifier> pending = originalBatch;
        for (int attempt = 1; attempt <= 3 && !pending.isEmpty(); attempt++) {
            DeleteObjectsResponse response;
            try {
                java.util.List<ObjectIdentifier> requestBatch = pending;
                response = circuitBreaker.executeSupplier(() -> client.deleteObjects(
                        DeleteObjectsRequest.builder()
                                .bucket(bucket)
                                .delete(d -> d.objects(requestBatch))
                                .build()));
            } catch (RuntimeException ex) {
                if (attempt == 3) {
                    throw ex;
                }
                // The request outcome is ambiguous; repeat only this batch.
                continue;
            }

            if (response == null) {
                if (attempt == 3) {
                    throw new IllegalStateException("S3 returned no DeleteObjects response");
                }
                continue;
            }
            if (response.errors() == null || response.errors().isEmpty()) {
                return;
            }

            java.util.List<ObjectIdentifier> failed = new java.util.ArrayList<>();
            for (var error : response.errors()) {
                if (error.key() == null) {
                    throw new IllegalStateException("S3 returned a per-key delete error without a key");
                }
                String errorVersionId = error.versionId();
                ObjectIdentifier original = originalBatch.stream()
                        .filter(item -> error.key().equals(item.key())
                                && java.util.Objects.equals(errorVersionId, item.versionId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "S3 returned a delete error for an identifier not in the request: "
                                        + error.key() + " versionId=" + errorVersionId));
                failed.add(original);
            }

            if (attempt == 3) {
                var first = response.errors().get(0);
                throw new IllegalStateException("S3 could not delete " + failed.size()
                        + " object version(s) after three attempts (first: key='" + first.key()
                        + "', versionId='" + first.versionId() + "', code='" + first.code()
                        + "', message='" + first.message() + "')");
            }
            pending = failed.stream()
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toMap(
                                    item -> item.key() + "\u0000" + item.versionId(),
                                    item -> item,
                                    (left, right) -> left,
                                    java.util.LinkedHashMap::new),
                            map -> java.util.List.copyOf(map.values())));
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
