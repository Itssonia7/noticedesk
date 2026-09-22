package com.noticedesk.api.service.ocr;

import com.noticedesk.api.service.IdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class OcrQualityGateServiceTest {

    private OcrQualityGateService qualityGateService;
    private IdentityService identityService;

    @BeforeEach
    void setUp() {
        identityService = new IdentityService(null);
        qualityGateService = new OcrQualityGateService(identityService);
    }

    @Test
    void testHighQualityNoticeText() {
        String clearNoticeText = """
                FORM GST DRC-01
                SHOW CAUSE NOTICE UNDER SECTION 73 OF THE CGST ACT, 2017
                To Taxpayer: M/s ACME TRADING PVT LTD
                GSTIN: 27AAACA1234A1Z5
                
                Whereas on verification of GSTR-3B and GSTR-2A for the tax period 2018-19, it is observed that
                there is an Input Tax Credit mismatch amounting to Rupees 1,50,000. Demand under Section 73 is proposed
                along with interest and penalty. Hearing date is scheduled for 15-10-2024.
                """;

        OcrQualityGateService.OcrQualityResult result = qualityGateService.assessQuality(clearNoticeText, 1, "gemini");

        assertEquals(OcrQualityGateService.QualityStatus.HIGH_QUALITY, result.status());
        assertTrue(result.qualityScore() >= 0.75);
        assertFalse(result.isVisionFallbackRecommended());
        assertTrue(result.failureReasons().isEmpty());
    }

    @Test
    void testLowQualitySmudgedText() {
        String garbledText = "N0t1c3 #$% 16(2) ### ??? x@9f";

        OcrQualityGateService.OcrQualityResult result = qualityGateService.assessQuality(garbledText, 2, "gemini");

        assertEquals(OcrQualityGateService.QualityStatus.LOW_QUALITY_SCAN, result.status());
        assertTrue(result.qualityScore() < 0.75);
        assertTrue(result.isVisionFallbackRecommended());
        assertFalse(result.failureReasons().isEmpty());
    }

    @Test
    void testEmptyTextHandling() {
        OcrQualityGateService.OcrQualityResult result = qualityGateService.assessQuality("", 1, "gemini");

        assertEquals(OcrQualityGateService.QualityStatus.LOW_QUALITY_SCAN, result.status());
        assertEquals(0.0, result.qualityScore());
        assertTrue(result.isVisionFallbackRecommended());
    }
}
