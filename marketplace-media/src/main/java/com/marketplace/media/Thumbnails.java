package com.marketplace.media;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
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
     * cross-format surprises, no alpha flattening, no alpha invention).
     *
     * <p><b>Raster budget (CodeRabbit #263 hardening):</b> the source header's
     * {@code width × height} is read through an {@link ImageReader} WITHOUT
     * raster decoding first; sources declaring more than
     * {@code maxSourcePixels} pixels are never decoded — the caller keeps the
     * original as its own thumbnail (a highly compressed image inside the
     * upload byte cap can otherwise allocate a gigabyte-scale raster on the
     * asynchronous listener thread). The header-only dimension read is the
     * official ImageReader contract: {@code getWidth}/{@code getHeight} read
     * from the stream header.
     *
     * <p><b>Target image type (CodeRabbit #263 hardening):</b> the scaled
     * target always uses one of the two explicit standard types — chosen from
     * {@code source.getColorModel().hasAlpha()} (ARGB when the source carries
     * alpha, RGB otherwise). It is deliberately NEVER derived from
     * {@code source.getType()}: ImageIO can return {@code TYPE_CUSTOM} (0) for
     * valid images read through custom color models, and the
     * {@code BufferedImage(int, int, int)} constructor rejects that value with
     * {@code IllegalArgumentException} — a failure the caller's IOException
     * contract does not cover.
     *
     * @return the scaled encoded bytes, or {@code null} when no real thumbnail
     *         is needed — the original is already within the width bound, or
     *         its header declares more pixels than the budget; the caller then
     *         points the thumbnail at the original object instead of storing a
     *         duplicate (an already-small or oversized-raster image needs no
     *         copy).
     * @throws IOException when the bytes cannot be read as an image — the
     *         caller treats this as a processing failure (publication FAILED,
     *         retried by the documented resubmission machinery, debt D3).
     * @throws ThumbnailEncodingException when the scaled image cannot be
     *         re-encoded — an IOException subclass that carries the stage
     *         (the write half of the image math) so the caller's D3 failure
     *         counter can distinguish {@code encode} from {@code decode}
     *         failures. The realistic case is a declared-content-type /
     *         actual-bytes mismatch (PNG-with-alpha bytes under a jpeg
     *         declaration die at the JPEG writer — measured).
     */
    static byte[] scaleToMaxWidth(byte[] encoded, int maxWidth, String contentType,
                                  long maxSourcePixels) throws IOException {
        int[] header = headerDimensions(encoded);
        if ((long) header[0] * header[1] > maxSourcePixels) {
            // Raster budget exceeded — never decode (header-only read above).
            return null;
        }
        if (header[0] <= maxWidth) {
            // Within the width bound — no scaling needed, no duplicate stored.
            return null;
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(encoded));
        if (source == null) {
            // No registered reader accepted the bytes (corrupt/empty) — a
            // processing failure by contract, not a silent fallback.
            throw new IOException("No ImageIO reader accepted the media bytes");
        }
        double scale = (double) maxWidth / source.getWidth();
        int targetWidth = maxWidth;
        int targetHeight = (int) Math.round(source.getHeight() * scale);
        // Explicit standard target type from the source's alpha presence —
        // never source.getType(): TYPE_CUSTOM (0) would make the constructor
        // below throw IllegalArgumentException (outside the IOException
        // contract), and the type never encodes anything the format needs.
        int targetType = source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, targetType);
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
     * Reads the pixel dimensions of the encoded image from its header — used
     * by the raster budget gate, tests and diagnostics; same failure contract
     * as {@link #scaleToMaxWidth}. Header-only: no raster is decoded.
     */
    static int[] dimensions(byte[] encoded) throws IOException {
        return headerDimensions(encoded);
    }

    /**
     * The header-only dimension read (official ImageReader contract): picks
     * the registered reader for the stream and asks it for
     * {@code getWidth(0)} / {@code getHeight(0)} — both answered from the
     * format header, so a gigapixel declaration costs a few bytes of parsing,
     * not the raster allocation a full {@link ImageIO#read} would make.
     */
    private static int[] headerDimensions(byte[] encoded) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            if (input == null) {
                throw new IOException("No ImageIO stream for the media bytes");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No ImageIO reader accepted the media bytes");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException("Image header carries no usable dimensions");
                }
                return new int[]{width, height};
            } finally {
                reader.dispose();
            }
        }
    }

    private static byte[] encode(BufferedImage image, String contentType) throws IOException {
        String formatName = switch (contentType) {
            case "image/png" -> "png";
            default -> "jpeg";
        };
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
        if (!writers.hasNext()) {
            throw new ThumbnailEncodingException("No ImageIO writer for " + contentType);
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // try-with-resources on the stream (CodeRabbit #263 hardening):
            // ImageWriter.write does not guarantee flushing the configured
            // output — only close() does. Reading out.toByteArray() while the
            // stream is open can return incomplete bytes.
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(stream);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if ("image/jpeg".equals(contentType) && param.canWriteCompressed()) {
                    // JPEG: explicit mode first, then quality — the ImageWriteParam
                    // contract (setCompressionQuality requires MODE_EXPLICIT).
                    // PNG keeps its format-default compression.
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.85f);
                }
                writer.write(null, new IIOImage(image, null, null), param);
            } catch (IOException ex) {
                // ENCODE-stage failure (D3 counter): the writer rejected the
                // image — e.g. an ARGB raster under the JPEG writer, the
                // measured "Bogus input colorspace" of a declared-type /
                // actual-bytes mismatch — or the output stream failed.
                throw new ThumbnailEncodingException(
                        "Encoding the scaled thumbnail failed: " + ex.getMessage(), ex);
            }
        } finally {
            writer.dispose();
        }
        // Read AFTER the stream is closed — every buffered byte is flushed.
        return out.toByteArray();
    }
}
