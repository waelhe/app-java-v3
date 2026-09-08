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
 * through scaling.
 */
class ThumbnailsTest {

    /** Generous budget for the normal-path tests — the budget itself has
     *  dedicated tests below. */
    private static final long UNLIMITED = 100_000_000L;

    /** Renders a deterministic w×h image and encodes it as JPEG or PNG bytes. */
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
        String format = "image/png".equals(contentType) ? "png" : "jpeg";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
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
}
