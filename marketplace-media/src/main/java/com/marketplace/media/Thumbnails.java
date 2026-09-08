package com.marketplace.media;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/**
 * L28 (feature-expansion roadmap §5): pure image math for the thumbnail —
 * JDK-standard ImageIO only, zero new dependencies (the roadmap's explicit
 * constraint). Static and side-effect-free so it is unit-testable without
 * Spring or storage.
 *
 * <p>Processable set: {@code image/jpeg} and {@code image/png} — the two
 * allowlisted types the JDK both reads AND writes natively. {@code image/webp}
 * has no JDK ImageIO writer and {@code image/gif} would silently collapse to
 * a static first frame; both are treated as non-processable by the caller
 * (thumbnail = original, documented in the service).
 */
final class Thumbnails {

    private static final Set<String> PROCESSABLE_TYPES = Set.of("image/jpeg", "image/png");

    private Thumbnails() {
    }

    /**
     * Whether this content type gets a real scaled thumbnail. Anything else
     * keeps {@code thumb = original} per the roadmap acceptance (4).
     */
    static boolean isProcessable(String contentType) {
        return PROCESSABLE_TYPES.contains(contentType);
    }

    /**
     * Scales the encoded image down to at most {@code maxWidth} pixels wide
     * (aspect ratio preserved, bilinear interpolation) and re-encodes it in
     * the SAME format — PNG stays lossless PNG, JPEG stays JPEG (no
     * cross-format surprises, no alpha flattening).
     *
     * @return the scaled encoded bytes, or {@code null} when the original is
     *         already within the bound — the caller then points the thumbnail
     *         at the original object instead of storing a duplicate (an
     *         already-small image needs no copy).
     * @throws IOException when the bytes cannot be read as an image — the
     *         caller treats this as a processing failure (publication FAILED,
     *         retried by the documented resubmission machinery, debt D3).
     */
    static byte[] scaleToMaxWidth(byte[] encoded, int maxWidth, String contentType) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(encoded));
        if (source == null) {
            // No registered reader accepted the bytes (corrupt/empty) — a
            // processing failure by contract, not a silent fallback.
            throw new IOException("No ImageIO reader accepted the media bytes");
        }
        if (source.getWidth() <= maxWidth) {
            return null;
        }
        double scale = (double) maxWidth / source.getWidth();
        int targetWidth = maxWidth;
        int targetHeight = (int) Math.round(source.getHeight() * scale);
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, source.getType());
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, AffineTransform.getScaleInstance(scale, scale), null);
        } finally {
            graphics.dispose();
        }
        return encode(target, contentType);
    }

    /**
     * Reads the pixel dimensions of the encoded image — used by tests and
     * diagnostics; same failure contract as {@link #scaleToMaxWidth}.
     */
    static int[] dimensions(byte[] encoded) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(encoded));
        if (source == null) {
            throw new IOException("No ImageIO reader accepted the media bytes");
        }
        return new int[]{source.getWidth(), source.getHeight()};
    }

    private static byte[] encode(BufferedImage image, String contentType) throws IOException {
        String formatName = switch (contentType) {
            case "image/png" -> "png";
            default -> "jpeg";
        };
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
        if (!writers.hasNext()) {
            throw new IOException("No ImageIO writer for " + contentType);
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writer.setOutput(ImageIO.createImageOutputStream(out));
            ImageWriteParam param = writer.getDefaultWriteParam();
            if ("image/jpeg".equals(contentType) && param.canWriteCompressed()) {
                // JPEG: explicit mode first, then quality — the ImageWriteParam
                // contract (setCompressionQuality requires MODE_EXPLICIT).
                // PNG keeps its format-default compression.
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.85f);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
