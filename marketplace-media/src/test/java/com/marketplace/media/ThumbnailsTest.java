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
 * already-small originals answered as "no duplicate needed" (null).
 */
class ThumbnailsTest {

    /** Renders a deterministic w×h image and encodes it as JPEG or PNG bytes. */
    private static byte[] render(int width, int height, String contentType) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 31 + y * 17) % 256) << 16);
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

        byte[] scaled = Thumbnails.scaleToMaxWidth(original, 640, "image/jpeg");

        assertThat(scaled).isNotNull();
        assertThat(dims(scaled)).containsExactly(640, 320);
        assertThat(dims(scaled)[0]).isLessThan(dims(original)[0]);
    }

    @Test
    @DisplayName("Non-round aspect still lands inside the bound with aspect preserved")
    void nonRoundAspectScales() throws Exception {
        byte[] scaled = Thumbnails.scaleToMaxWidth(render(3000, 900, "image/jpeg"), 640, "image/jpeg");

        assertThat(scaled).isNotNull();
        assertThat(dims(scaled)).containsExactly(640, 192);
    }

    @Test
    @DisplayName("PNG stays PNG (lossless format kept, correct dimensions)")
    void pngStaysPng() throws Exception {
        byte[] scaled = Thumbnails.scaleToMaxWidth(render(1280, 800, "image/png"), 640, "image/png");

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
        assertThat(Thumbnails.scaleToMaxWidth(render(640, 400, "image/jpeg"), 640, "image/jpeg"))
                .as("exactly at the bound is within the bound")
                .isNull();
        assertThat(Thumbnails.scaleToMaxWidth(render(320, 200, "image/png"), 640, "image/png"))
                .isNull();
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
    @DisplayName("Bytes no ImageIO reader accepts are a processing failure, not a silent fallback")
    void corruptBytesThrow() {
        assertThatThrownBy(() -> Thumbnails.scaleToMaxWidth("not-an-image".getBytes(), 640, "image/jpeg"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("dimensions() reports the encoded image size")
    void dimensionsRead() throws Exception {
        assertThat(Thumbnails.dimensions(render(100, 50, "image/png"))).containsExactly(100, 50);
    }
}
