package com.noticedesk.api.service.matching;

import com.noticedesk.api.service.matching.StageResolver.DocumentKind;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Input-document to response-stage table used by the post-matching stage correction (same table as the matching prompt). */
class StageResolverTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "DRC-01            | REPLY",
            "DRC-01A           | REPLY",
            "drc_01a           | REPLY",
            "ASMT-10           | REPLY",
            "asmt_10           | REPLY",
            "ADT-01            | REPLY",
            "REG-17            | REPLY",
            "rfd_08            | REPLY",
            "RFD-08            | REPLY",
            "MOV-07            | REPLY",
            "Summons           | REPLY",
            "Section 46 notice | REPLY",
            "Section 62 order  | REPRESENTATION",
            "DRC-22            | OBJECTION",
            "DRC-13            | REPRESENTATION",
            "recovery letter   | REPRESENTATION",
            "DRC-07            | APPEAL",
            "RFD-06            | APPEAL",
            "RFD 06            | APPEAL",
            "REG-05            | APPEAL",
            "MOV-09            | APPEAL",
            "Order-in-Original | APPEAL",
            "Order in Appeal   | APPEAL",
            "REG-19            | REVOCATION",
            "scn_73            | REPLY",
            "scn_74            | REPLY",
    })
    void documentTypeMapping(String documentType, ResponseStage expected) {
        assertEquals(expected, StageResolver.resolve(documentType, null));
    }

    @Test
    void unknownInputGivesNoStage() {
        assertNull(StageResolver.resolve(null, null));
        assertNull(StageResolver.resolve("needs_review", "Some letter without a form name"));
        assertNull(StageResolver.resolve("", ""));
        assertNull(StageResolver.documentKind("needs_review", null));
    }

    @Test
    void headingOverridesTheGenericScnTriageLabel() {
        String rfd06 = "FORM-GST-RFD-06 [See rule 92(1), 92(3), 92(4), 92(5) and 96(7)]\nRefund Sanction/Rejection Order\n"
                + "Reference No. of the show cause notice in FORM GST RFD-08 dated ...";
        assertEquals(ResponseStage.APPEAL, StageResolver.resolve("scn_73", rfd06));
        assertEquals(DocumentKind.ORDER, StageResolver.documentKind("scn_73", rfd06));
        // no form in the heading: the triage label stands
        assertEquals(ResponseStage.REPLY, StageResolver.resolve("scn_73", "Notice regarding tax short paid"));
        assertEquals(ResponseStage.APPEAL, StageResolver.resolve("needs_review", "ORDER-IN-ORIGINAL No. 12/2024 passed under Section 75"));
    }

    @Test
    void specificDocumentTypeWinsOverTheHeading() {
        // a DRC-01 SCN that refers to the earlier DRC-01A stays a reply to an SCN
        assertEquals(ResponseStage.REPLY, StageResolver.resolve("DRC-01", "FORM GST DRC-07 referred to in para 9"));
        assertEquals(DocumentKind.SCN, StageResolver.documentKind("DRC-01", null));
    }

    @Test
    void firstFormInTheHeadingWins() {
        String drc07 = "FORM GST DRC-07 [See rule 142(5)] Summary of the order\nReference: SCN in FORM GST DRC-01 dated 02.05.2024";
        assertEquals(ResponseStage.APPEAL, StageResolver.resolve(null, drc07));
        String drc01 = "FORM GST DRC-01 Summary of Show Cause Notice; an order in FORM GST DRC-07 may follow";
        assertEquals(ResponseStage.REPLY, StageResolver.resolve(null, drc01));
    }

    @Test
    void documentKinds() {
        assertEquals(DocumentKind.INTIMATION, StageResolver.documentKind("ASMT-10", null));
        assertEquals(DocumentKind.INTIMATION, StageResolver.documentKind("DRC-01A", null));
        assertEquals(DocumentKind.ORDER, StageResolver.documentKind("DRC-07", null));
        assertEquals(DocumentKind.SCN, StageResolver.documentKind("scn_73", "Show Cause Notice under Section 73"));
        assertEquals(DocumentKind.LETTER, StageResolver.documentKind(null, "Recovery letter for the confirmed demand"));
        assertEquals("Order", DocumentKind.ORDER.label());
    }
}
