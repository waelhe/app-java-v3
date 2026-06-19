package com.marketplace.identity;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link QrCodeService}.
 *
 * <p>Verifies that the generated QR code is a real, scannable QR code by
 * decoding it back with ZXing's {@link QRCodeReader} — a round-trip test
 * that no authenticator app would accept a fake hash-pattern image.
 *
 * @see <a href="https://github.com/zxing/zxing">ZXing</a>
 */
class QrCodeServiceTest {

    private final QrCodeService qrCodeService = new QrCodeService();

    @Test
    void generateQrCodePng_returnsPngDataUri() {
        String result = qrCodeService.generateQrCodePng("otpauth://totp/test");
        assertNotNull(result);
        assertTrue(result.startsWith("data:image/png;base64,"));
        // Decode the Base64 payload — the first 8 bytes must be the PNG signature.
        String b64 = result.substring("data:image/png;base64,".length());
        byte[] bytes = Base64.getDecoder().decode(b64);
        // PNG signature: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
        assertEquals((byte) 0x89, bytes[0], "First byte should be PNG signature 0x89");
        assertEquals((byte) 0x50, bytes[1], "Second byte should be 'P'");
        assertEquals((byte) 0x4E, bytes[2], "Third byte should be 'N'");
        assertEquals((byte) 0x47, bytes[3], "Fourth byte should be 'G'");
    }

    @Test
    void generateQrCodePng_isDecodableByZxing() throws Exception {
        // Round-trip test: encode a known otpauth URI, decode it back, verify content.
        String originalUri = "otpauth://totp/Marketplace:user@test.com?secret=JBSWY3DPEHPK3PXP&issuer=Marketplace&digits=6&period=30";
        String dataUri = qrCodeService.generateQrCodePng(originalUri);

        String b64 = dataUri.substring("data:image/png;base64,".length());
        byte[] pngBytes = Base64.getDecoder().decode(b64);

        var image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertNotNull(image, "Decoded PNG bytes should produce a valid BufferedImage");

        var source = new BufferedImageLuminanceSource(image);
        var bitmap = new BinaryBitmap(new HybridBinarizer(source));
        var hints = new java.util.HashMap<DecodeHintType, Object>();
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(com.google.zxing.BarcodeFormat.QR_CODE));
        var result = new MultiFormatReader().decode(bitmap, hints);

        assertEquals(originalUri, result.getText(),
                "ZXing must decode the QR code back to the original otpauth URI");
    }

    @Test
    void generateQrCodePng_nonEmptyForDifferentInputs() {
        String r1 = qrCodeService.generateQrCodePng("input1");
        String r2 = qrCodeService.generateQrCodePng("input2");
        assertNotNull(r1);
        assertNotNull(r2);
        assertNotEquals(r1, r2, "Different inputs should produce different QR codes");
    }

    @Test
    void generateQrCodeData_returnsBothUriAndDataUri() {
        String otpAuthUri = "otpauth://totp/test";
        Map<String, String> result = qrCodeService.generateQrCodeData(otpAuthUri);
        assertNotNull(result.get("qrCodeDataUri"));
        assertNotNull(result.get("otpAuthUri"));
        assertEquals(otpAuthUri, result.get("otpAuthUri"));
        assertTrue(result.get("qrCodeDataUri").startsWith("data:image/png;base64,"));
    }

    @Test
    void generateQrCodePng_throwsForExtremelyLongInput() {
        // ZXing has a maximum capacity; an absurdly long string should fail predictably.
        String tooLong = "x".repeat(10_000);
        assertThrows(IllegalStateException.class, () -> qrCodeService.generateQrCodePng(tooLong));
    }
}
