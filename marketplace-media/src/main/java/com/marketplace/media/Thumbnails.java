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

    /**
     * The decoder's format permit — the canonical {@code getFormatName()}
     * values of the JDK's JPEG and PNG reader plugins ("JPEG" and "png").
     * The declared MIME type only gates <em>processing</em> (whether a
     * thumbnail is attempted at all — {@link #isProcessable}); the DECODER
     * is selected from the actual bytes, so the stream's own plugin must
     * be verified to sit inside the same two-format permit before any
     * raster work (row 4, agent findings 2026-10-25). A crafted stream in
     * any other registered format — a BMP whose {@code imageSize} header
     * field commands a gigabyte-scale allocation that the budget-checked
     * {@code width × height} never sees — is refused at reader selection
     * and never reaches its decoder.
     */
    private static final Set<String> PERMITTED_READER_FORMATS = Set.of("JPEG", "png");

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
     * <p><b>Decoder format permit (row 4, agent findings 2026-10-25):</b>
     * the reader is selected ONCE, its {@code getFormatName()} (the official
     * ImageIO disclosure of which plugin will decode the stream) is verified
     * against the JPEG/PNG permit, and the raster is decoded through that
     * SAME validated reader. The former {@code ImageIO.read()} re-selected a
     * reader from the raw bytes with no permit check, so crafted bytes in a
     * non-permitted registered format could pass the header budget on small
     * {@code width × height} and still reach their own decoder — the BMP
     * reader allocates from the separate {@code imageSize} header field,
     * outside the raster budget's math. Bytes outside the permit fail at the
     * DECODE stage exactly like unreadable bytes: a processing failure the
     * caller counts and the documented resubmission machinery owns (never a
     * silent fallback, and never a mislabeled original served as its own
     * thumbnail).
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
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            if (input == null) {
                throw new IOException("No ImageIO stream for the media bytes");
            }
            // ONE reader selection for the whole pass — format-permitted up
            // front (row 4), header-read for the budget, and the raster
            // decode through the SAME validated plugin.
            ImageReader reader = selectPermittedReader(input);
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException("Image header carries no usable dimensions");
                }
                if ((long) width * height > maxSourcePixels) {
                    // Raster budget exceeded — never decode (header-only read above).
                    return null;
                }
                if (width <= maxWidth) {
                    // Within the width bound — no scaling needed, no duplicate stored.
                    return null;
                }
                BufferedImage source = reader.read(0);
                if (source == null) {
                    // Defensive: the permitted reader decoded no raster — a
                    // processing failure by contract, not a silent fallback.
                    throw new IOException("The permitted reader decoded no raster");
                }
                return scaleAndEncode(source, maxWidth, contentType);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * The scaling half (unchanged math): bilinear, aspect preserved,
     * same-format re-encode — split out so the reader lifecycle stays
     * entirely inside {@link #scaleToMaxWidth}'s try-with-resources.
     */
    private static byte[] scaleAndEncode(BufferedImage source, int maxWidth, String contentType)
            throws IOException {
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
     * by tests and diagnostics; same failure contract as
     * {@link #scaleToMaxWidth}, INCLUDING the JPEG/PNG reader permit (row 4):
     * a stream in any other registered format is refused before its reader
     * runs, header reads included. Header-only: no raster is decoded.
     */
    static int[] dimensions(byte[] encoded) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            if (input == null) {
                throw new IOException("No ImageIO stream for the media bytes");
            }
            ImageReader reader = selectPermittedReader(input);
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

    /**
     * The format permit's gate (row 4): ImageIO picks the plugin by sniffing
     * the bytes (the official reader-selection contract —
     * {@link ImageIO#getImageReaders}), and the FIRST accepting reader is
     * checked against {@link #PERMITTED_READER_FORMATS} through its
     * {@link ImageReader#getFormatName()} BEFORE any header or raster work.
     * A reader outside the permit is disposed and refused with the decode-stage
     * IOException — the caller's failure contract (publication FAILED, retried
     * by the documented machinery; the same never-silent-drop rule as
     * unreadable bytes). {@code equalsIgnoreCase} on the canonical names
     * so a differently-cased registration of the SAME plugin still passes,
     * while every other format (bmp, gif, tiff, wbmp, "jpeg 2000", …) fails
     * the equality outright.
     */
    private static ImageReader selectPermittedReader(ImageInputStream input) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            // No registered reader accepted the bytes (corrupt/empty) — a
            // processing failure by contract, not a silent fallback.
            throw new IOException("No ImageIO reader accepted the media bytes");
        }
        ImageReader reader = readers.next();
        String format = reader.getFormatName();
        boolean permitted = PERMITTED_READER_FORMATS.stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(format));
        if (!permitted) {
            reader.dispose();
            throw new IOException("Image format '" + format
                    + "' is outside the permitted JPEG/PNG set — refusing to decode");
        }
        return reader;
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
