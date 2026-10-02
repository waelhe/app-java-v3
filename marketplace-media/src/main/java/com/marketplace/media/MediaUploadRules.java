package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ServiceUnavailableException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * W1 (§4.4): the upload rules both media services share — the storage
 * gate, the content-type allowlist normalization, the size bound and the
 * server-generated object key. Extracted from {@code MediaService} when
 * the review-media path arrived: the two flows are the SAME contract
 * ({@code MEDIA_MAX_UPLOAD_BYTES}, the allowed-type list, the
 * {@code {prefix}/{ownerId}/{uuid}.{ext}} key shape) and a second copy
 * would let one side drift silently from the other.
 */
final class MediaUploadRules {

    /** Server-controlled extension mapping — the client never touches the key. */
    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    private MediaUploadRules() {
    }

    /** The provider gate: absent storage (empty MEDIA_S3_* placeholders) answers the honest 503. */
    static S3MediaStorage requireStorage(ObjectProvider<S3MediaStorage> storage) {
        S3MediaStorage s3 = storage.getIfAvailable();
        if (s3 == null) {
            throw new ServiceUnavailableException(
                    "Media storage is not configured. Set MEDIA_S3_ENDPOINT, MEDIA_S3_BUCKET, "
                            + "MEDIA_S3_ACCESS_KEY and MEDIA_S3_SECRET_KEY to enable media.");
        }
        return s3;
    }

    static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BadRequestException("Content type is required");
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    static void validateContentType(MediaProperties properties, String normalizedType) {
        if (!properties.limits().allowedContentTypes().contains(normalizedType)) {
            throw new BadRequestException(
                    "Unsupported media content type: " + normalizedType
                            + " (allowed: " + properties.limits().allowedContentTypes() + ")");
        }
    }

    static void validateSize(MediaProperties properties, long sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes > properties.limits().maxUploadBytes()) {
            throw new BadRequestException(
                    "Media size " + sizeBytes + " bytes is outside the allowed range (max "
                            + properties.limits().maxUploadBytes() + ")");
        }
    }

    static String buildObjectKey(String prefix, UUID ownerId, String contentType) {
        String extension = EXTENSION_BY_TYPE.getOrDefault(contentType, "bin");
        return prefix + "/" + ownerId + "/" + UUID.randomUUID() + "." + extension;
    }
}
