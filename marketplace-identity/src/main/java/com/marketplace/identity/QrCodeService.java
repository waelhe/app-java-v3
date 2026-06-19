package com.marketplace.identity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates QR codes as Base64-encoded PNG images from otpauth URIs.
 *
 * <p>Uses <strong>ZXing</strong> ("zebra crossing") -- the de-facto standard
 * open-source QR code library -- to produce <em>scannable</em> QR codes that
 * Google Authenticator, Microsoft Authenticator, Authy, and 1Password can decode.
 *
 * <p>This replaces the earlier placeholder hash-pattern implementation, which
 * produced images that no authenticator app could decode. The TOTP enrollment
 * flow is therefore end-to-end functional.
 *
 * <p><b>References</b>
 * <ul>
 *   <li><a href="https://github.com/zxing/zxing">ZXing GitHub</a></li>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc6238">RFC 6238 (TOTP)</a></li>
 *   <li><a href="https://github.com/google/google-authenticator/wiki/Key-Uri-Format">Google Authenticator Key URI Format</a></li>
 *   <li><a href="https://docs.spring.io/spring-boot/how-to/deployment/index.html">Spring Boot Deployment</a></li>
 * </ul>
 */
@Service
public class QrCodeService {

    private static final Logger log = LoggerFactory.getLogger(QrCodeService.class);

    /** QR image width in pixels. */
    private static final int WIDTH = 240;
    /** QR image height in pixels. */
    private static final int HEIGHT = 240;
    /** PNG MIME marker used in the data URI. */
    private static final String PNG_DATA_URI_PREFIX = "data:image/png;base64,";

    private final QRCodeWriter qrCodeWriter = new QRCodeWriter();

    /**
     * Generates a Base64-encoded PNG QR code from the given text (typically an
     * otpauth:// URI).
     *
     * @param text the text to encode (e.g. otpauth:// URI)
     * @return data URI: {@code data:image/png;base64,...}
     */
    public String generateQrCodePng(String text) {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);

        try {
            BitMatrix matrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, WIDTH, HEIGHT, hints);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                MatrixToImageWriter.writeToStream(matrix, "PNG", out);
                return PNG_DATA_URI_PREFIX + Base64.getEncoder().encodeToString(out.toByteArray());
            }
        } catch (WriterException | IOException e) {
            // EncodeHintType.CHARACTER_SET UTF-8 is always available, so WriterException
            // here indicates a payload too large for the QR matrix at the requested size.
            throw new IllegalStateException("Failed to generate QR code", e);
        }
    }

    /**
     * Returns QR code data URI + otpauth URI for frontend display.
     *
     * @param otpAuthUri otpauth:// URI to embed in the QR code
     * @return map with {@code qrCodeDataUri} (PNG data URI) and {@code otpAuthUri} (raw URI)
     */
    public Map<String, String> generateQrCodeData(String otpAuthUri) {
        Map<String, String> data = new HashMap<>();
        data.put("qrCodeDataUri", generateQrCodePng(otpAuthUri));
        data.put("otpAuthUri", otpAuthUri);
        log.debug("Generated QR code for otpauth URI (length={})", otpAuthUri.length());
        return data;
    }
}
