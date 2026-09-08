package com.marketplace.media;

import java.io.IOException;

/**
 * The thumbnail pipeline's <b>encode-stage</b> failure — an {@link IOException}
 * subclass so {@code MediaService.processThumbnail} can distinguish the write
 * half of the image math from the read half for the D3 failure counter
 * ({@code MediaThumbnailMetrics.FailureReason#ENCODE} vs
 * {@code DECODE}). Thrown by {@code Thumbnails.encode} when no writer exists
 * for the declared format or when the writer rejects the image — the
 * realistic production case is a declared-content-type/actual-bytes
 * mismatch: PNG-with-alpha bytes uploaded under an {@code image/jpeg}
 * declaration decode fine, then die at the JPEG writer with "Bogus input
 * colorspace" (measured against the exact production write path).
 *
 * <p>Stage-carrying, not behavior-carrying: callers that catch plain
 * {@code IOException} keep the exact pre-existing behavior; only callers
 * that need the stage distinction notice the subclass.
 */
class ThumbnailEncodingException extends IOException {

    ThumbnailEncodingException(String message) {
        super(message);
    }

    ThumbnailEncodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
