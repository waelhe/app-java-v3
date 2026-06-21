package com.marketplace.identity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QrCodeServiceTest {

    private final QrCodeService qrCodeService = new QrCodeService();

    @Test
    void generateQrCodeSvg_returnsDataUri() {
        String result = qrCodeService.generateQrCodeSvg("otpauth://totp/test");
        assertNotNull(result);
        assertTrue(result.startsWith("data:image/svg+xml;base64,"));
    }

    @Test
    void generateQrCodeSvg_nonEmptyForDifferentInputs() {
        String r1 = qrCodeService.generateQrCodeSvg("input1");
        String r2 = qrCodeService.generateQrCodeSvg("input2");
        assertNotNull(r1);
        assertNotNull(r2);
    }

    @Test
    void generateQrCodeData_returnsBothUriAndDataUri() {
        Map<String, String> result = qrCodeService.generateQrCodeData("otpauth://totp/test");
        assertNotNull(result.get("qrCodeDataUri"));
        assertNotNull(result.get("otpAuthUri"));
        assertEquals("otpauth://totp/test", result.get("otpAuthUri"));
    }
}
