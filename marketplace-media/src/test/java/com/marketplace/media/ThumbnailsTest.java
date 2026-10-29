package com.marketplace.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L28 (feature-expansion roadmap §5): pure unit tests of the thumbnail math —
 * JDK ImageIO only, no Spring, no storage. The acceptance examples are
 * numeric and live here: width bound honored, aspect preserved, format kept,
 * already-small originals answered as "no duplicate needed" (null), plus the
 * CodeRabbit #263 hardening contracts: raster budget bypass, alpha preserved
 * through scaling — and the row-4 decoder format permit (agent findings
 * 2026-10-25): a stream outside JPEG/PNG is refused at reader selection,
 * before any of its plugin's work runs.
 */
class ThumbnailsTest {

    /** Generous budget for the normal-path tests — the budget itself has
     *  dedicated tests below. */
    private static final long UNLIMITED = 100_000_000L;

    /**
     * Renders a deterministic w×h image and encodes it as JPEG or PNG bytes.
     * Any OTHER ImageIO format name (bmp, gif, tiff, …) passes through so
     * the row-4 permit tests can forge the finding's lying-declaration
     * uploads with REAL bytes from the JDK's own writers.
     */
    private static byte[] render(int width, int height, String contentType) throws IOException {
        return render(width, height, contentType, false);
    }

    /** Same, with an alpha channel when requested (PNG only). */
    private static byte[] render(int width, int height, String contentType, boolean withAlpha)
            throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                withAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (((x * 31 + y * 17) % 256) << 16)
                        | (withAlpha ? 0x80000000 : 0));
            }
        }
        String format = switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpeg";
            default -> contentType; // a raw ImageIO format name (bmp/gif/tiff/wbmp)
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IllegalStateException("No ImageIO writer for " + format
                    + " in this JDK — the permit test cannot forge real bytes");
        }
        return out.toByteArray();
    }

    private static int[] dims(byte[] encoded) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
        return new int[]{image.getWidth(), image.getHeight()};
    }

    @Test
    @DisplayName("JPEG wider than the bound scales down to exactly maxWidth with aspect preserved")
    void wideJpegScales() throws Exception {
        byte[] original = render(2000, 1000, "image/jpeg");

        byte[] scaled = Thumbnails.scaleToMaxWidth(original, 640, "image/jpeg", UNLIMITED);

        assertThat(scaled).isNotNull();
        assertThat(dims(scaled)).containsExactly(640, 320);
        assertThat(dims(scaled)[0]).isLessThan(dims(original)[0]);
    }

    /**
     * Row 29 (agent findings 2026-10-26 14:33 export, CWE-400): a LEGITIMATE
     * progressive JPEG — the JDK writer's default progressive encoding
     * carries 10 scans (MEASURED) — stays inside the conservative 50-scan
     * ceiling and receives its thumbnail exactly like a baseline file; the
     * ceiling refuses only the abnormal scan counts whose repeated raster
     * reconstruction burns CPU disproportionate to the upload's byte size.
     */
    @Test
    @DisplayName("A default progressive JPEG (10 scans, measured) still scales — the ceiling refuses only abnormal scan counts")
    void progressiveJpegWithinTheMeasuredDefaultStillScales() throws Exception {
        // Progressive encoding through the writer's own contract: explicit
        // mode first (the ImageWriteParam rule), then the default table.
        BufferedImage image = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 1000; y++) {
            for (int x = 0; x < 2000; x++) {
                image.setRGB(x, y, (((x * 31 + y * 17) % 256) << 16));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = javax.imageio.ImageIO
                .getImageWritersByFormatName("jpeg").next();
        try (var stream = javax.imageio.ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        byte[] progressive = out.toByteArray();

        byte[] scaled = Thumbnails.scaleToMaxWidth(progressive, 640, "image/jpeg", UNLIMITED);

        assertThat(scaled).isNotNull();
        assertThat(dims(scaled)).containsExactly(640, 320);
    }

    @Test
    @DisplayName("Spec-allowed FF fill bytes before markers keep a healthy JPEG scaling (ITU T.81 B.1.1.2)")
    void fillBytesBeforeMarkersKeepTheWalkHonest() throws Exception {
        // Review round (CodeRabbit on the #757 sync, 2026-10-29): the JPEG
        // standard allows any number of FF padding bytes before a marker.
        // The pre-fix walk read the padding FF as the marker itself, then
        // consumed the REAL marker plus the next byte as a phantom segment
        // length — a ~55 KB jump that loses the true SOS and either
        // undercounts scans past the ceiling or falsely breaches the
        // segment cap on a perfectly healthy file. This regression pads
        // every marker of a real JPEG and expects the normal thumbnail.
        byte[] original = render(2000, 1000, "image/jpeg");
        java.io.ByteArrayOutputStream padded = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < original.length; i++) {
            int b = original[i] & 0xFF;
            int next = (i + 1 < original.length) ? (original[i + 1] & 0xFF) : -1;
            boolean markerFollows = b == 0xFF && next != 0x00 && next != 0xFF
                    && !(next >= 0xD0 && next <= 0xD7) && next != -1;
            if (markerFollows) {
                // Three spec-allowed fill bytes in front of the real marker.
                padded.write(0xFF);
                padded.write(0xFF);
                padded.write(0xFF);
            }
            padded.write(b);
        }
        byte[] paddedBytes = padded.toByteArray();

        byte[] scaled = Thumbnails.scaleToMaxWidth(paddedBytes, 640, "image/jpeg", UNLIMITED);

        assertThat(scaled).isNotNull();
        assertThat(dims(scaled)).containsExactly(640, 320);
    }

    @Test
    @DisplayName("Non-round aspect still lands inside the bound with aspect preserved")
    void nonRoundAspectScales() throws Exception {
        byte[] scaled = Thumbnails.scaleToMaxWidth(render(3000, 900, "image/jpeg"), 640, "image/jpeg", UNLIMITED);

        assertThat(scaled).isNotNull();
        assertThat(dims(scaled)).containsExactly(640, 192);
    }

    @Test
    @DisplayName("PNG stays PNG (lossless format kept, correct dimensions)")
    void pngStaysPng() throws Exception {
        byte[] scaled = Thumbnails.scaleToMaxWidth(render(1280, 800, "image/png"), 640, "image/png", UNLIMITED);

        assertThat(scaled).isNotNull();
        assertThat(dims(scaled)).containsExactly(640, 400);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(scaled));
        assertThat(decoded.getColorModel().hasAlpha())
                .as("a TYPE_INT_RGB source must not gain an alpha channel")
                .isFalse();
    }

    @Test
    @DisplayName("Original within the bound answers null — no duplicate object is stored")
    void smallOriginalNeedsNoThumbnail() throws Exception {
        assertThat(Thumbnails.scaleToMaxWidth(render(640, 400, "image/jpeg"), 640, "image/jpeg", UNLIMITED))
                .as("exactly at the bound is within the bound")
                .isNull();
        assertThat(Thumbnails.scaleToMaxWidth(render(320, 200, "image/png"), 640, "image/png", UNLIMITED))
                .isNull();
    }

    @Test
    @DisplayName("Source over the raster budget answers null — the original is its own thumbnail, never decoded")
    void oversizePixelBudgetBypassesScaling() throws Exception {
        // 2000×1000 = 2,000,000 pixels; budget 1,000,000 — header-only check.
        assertThat(Thumbnails.scaleToMaxWidth(render(2000, 1000, "image/jpeg"), 640, "image/jpeg", 1_000_000L))
                .as("a source declaring more pixels than the budget keeps thumb = original")
                .isNull();
        // Control: the same bytes within a generous budget still scale.
        assertThat(Thumbnails.scaleToMaxWidth(render(2000, 1000, "image/jpeg"), 640, "image/jpeg", UNLIMITED))
                .isNotNull();
    }

    @Test
    @DisplayName("A PNG carrying alpha keeps its alpha through scaling (explicit ARGB target)")
    void alphaSurvivesScaling() throws Exception {
        byte[] scaled = Thumbnails.scaleToMaxWidth(render(1280, 800, "image/png", true), 640, "image/png", UNLIMITED);

        assertThat(scaled).isNotNull();
        assertThat(dims(scaled)).containsExactly(640, 400);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(scaled));
        assertThat(decoded.getColorModel().hasAlpha())
                .as("the explicit target type must preserve the source's alpha")
                .isTrue();
    }

    @Test
    @DisplayName("Processable set is JPEG/PNG only — webp and gif keep thumb = original")
    void processableMatrix() {
        assertThat(Thumbnails.isProcessable("image/jpeg")).isTrue();
        assertThat(Thumbnails.isProcessable("image/png")).isTrue();
        assertThat(Thumbnails.isProcessable("image/webp")).isFalse();
        assertThat(Thumbnails.isProcessable("image/gif")).isFalse();
    }

    @Test
    @DisplayName("corrupt bytes fail at the DECODE stage — plain IOException")
    void corruptBytesThrow() {
        assertThatThrownBy(() -> Thumbnails.scaleToMaxWidth("not-an-image".getBytes(), 640, "image/jpeg", UNLIMITED))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(ThumbnailEncodingException.class);
    }

    @Test
    @DisplayName("A declared-type/actual-bytes mismatch fails at the ENCODE stage — ThumbnailEncodingException")
    void typeMismatchFailsAtEncodeStage() throws Exception {
        // PNG-with-alpha bytes under a jpeg declaration: the header read and
        // raster decode succeed (ImageIO picks the reader from the stream,
        // not the declared type), the scale succeeds, and the JPEG writer
        // rejects the ARGB raster ("Bogus input colorspace", measured
        // against the exact production write path) — the stage-carrying
        // subclass lets the D3 counter distinguish encode from decode.
        byte[] pngWithAlpha = render(800, 400, "image/png", true);

        assertThatThrownBy(() -> Thumbnails.scaleToMaxWidth(pngWithAlpha, 640, "image/jpeg", UNLIMITED))
                .isInstanceOf(ThumbnailEncodingException.class);
    }

    @Test
    @DisplayName("dimensions() reports the encoded image size")
    void dimensionsRead() throws Exception {
        assertThat(Thumbnails.dimensions(render(100, 50, "image/png"))).containsExactly(100, 50);
    }

    // ------------------------------------------------------------------
    // Row 4 (agent findings 2026-10-25) — the decoder format permit
    // ------------------------------------------------------------------

    /**
     * The finding's exact shape: REAL BMP bytes (the JDK ships both the
     * writer and the reader) under a processable declaration — small honest
     * width×height, so the raster budget and the width bound would both
     * pass and the decode would have reached the BMP reader, whose
     * allocation follows the separate imageSize header field. The permit is
     * on the ACTUAL encoded format, never the caller's declaration, and it
     * fires at reader selection — before any of the BMP plugin's work.
     */
    @Test
    @DisplayName("BMP bytes under a processable declaration are refused at reader selection (row 4)")
    void bmpBytesAreRefusedBeforeDecoding() throws Exception {
        byte[] bmp = render(2000, 1000, "bmp");

        assertThatThrownBy(() -> Thumbnails.scaleToMaxWidth(bmp, 640, "image/png", UNLIMITED))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(ThumbnailEncodingException.class)
                .hasMessageContaining("outside the permitted JPEG/PNG set");
    }

    /** The permit is the pair, not a BMP special case: gif likewise. */
    @Test
    @DisplayName("GIF bytes under a processable declaration are refused the same way")
    void gifBytesAreRefusedTheSameWay() throws Exception {
        byte[] gif = render(2000, 1000, "gif");

        assertThatThrownBy(() -> Thumbnails.scaleToMaxWidth(gif, 640, "image/jpeg", UNLIMITED))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the permitted JPEG/PNG set");
    }

    /**
     * The diagnostic surface enforces the same permit — a non-permitted
     * stream never even gets its header read through the refusing plugin.
     */
    @Test
    @DisplayName("dimensions() enforces the same permit — no non-permitted header reads either")
    void dimensionsEnforcesThePermit() throws Exception {
        assertThatThrownBy(() -> Thumbnails.dimensions(render(100, 50, "bmp")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the permitted JPEG/PNG set");
    }
}
