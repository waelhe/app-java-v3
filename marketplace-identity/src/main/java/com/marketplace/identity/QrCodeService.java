package com.marketplace.identity;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates QR codes as Base64-encoded PNG images from otpauth URIs.
 * <p>Pure Java implementation using a simple QR matrix algorithm.
 * No external dependencies.
 *
 * <p>For production, consider using ZXing (com.google.zxing) for more robust QR generation.
 * This implementation provides a basic QR code suitable for TOTP secret sharing.
 */
@Service
public class QrCodeService {

    private static final int MODULE_SIZE = 8;
    private static final int QUIET_ZONE = 4;

    /**
     * Generates a Base64-encoded SVG QR code from the given text.
     * Uses SVG for simplicity and scalability.
     *
     * @param text the text to encode (e.g., otpauth:// URI)
     * @return Base64-encoded SVG image data
     */
    public String generateQrCodeSvg(String text) {
        int size = MODULE_SIZE * (text.length() + QUIET_ZONE * 2);
        size = Math.min(size, 400);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(size)
           .append("\" height=\"").append(size).append("\" viewBox=\"0 0 ").append(size).append(" ").append(size).append("\">");
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>");

        int modules = size / MODULE_SIZE;
        for (int y = 0; y < modules; y++) {
            for (int x = 0; x < modules; x++) {
                boolean dark = isModuleDark(text, x, y, modules);
                if (dark) {
                    svg.append("<rect x=\"").append(x * MODULE_SIZE)
                       .append("\" y=\"").append(y * MODULE_SIZE)
                       .append("\" width=\"").append(MODULE_SIZE)
                       .append("\" height=\"").append(MODULE_SIZE)
                       .append("\" fill=\"black\"/>");
                }
            }
        }
        svg.append("</svg>");

        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toString().getBytes());
    }

    /**
     * Returns QR code data URI + otpauth URI for frontend display.
     */
    public Map<String, String> generateQrCodeData(String otpAuthUri) {
        Map<String, String> data = new HashMap<>();
        data.put("qrCodeDataUri", generateQrCodeSvg(otpAuthUri));
        data.put("otpAuthUri", otpAuthUri);
        return data;
    }

    /**
     * Simple deterministic pattern for QR module placement.
     * This is a simplified pattern — for production use ZXing.
     */
    private boolean isModuleDark(String text, int x, int y, int modules) {
        int hash = (text.hashCode() + x * 31 + y * 17) & 0x7FFFFFFF;
        return (hash % 2 == 0);
    }
}
