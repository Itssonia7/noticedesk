package com.noticedesk.api.service.matching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** StageDetector, NoticeParagraphs and PrayerStripper. */
class StageAndParagraphHelpersTest {

    @ParameterizedTest(name = "section={0} triage={1} -> {2}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Section 171       | scn_73  | anti_profiteering",
            "Section 171(1)    | NULL    | anti_profiteering",
            "Section 74        | scn_73  | scn_74",
            "Section 74        | scn_74  | scn_74",
            "Section 74A       | scn_73  | scn_74a",
            "Section 73        | scn_74  | scn_73",
            "Section 73        | NULL    | scn_73",
            "Section 61        | asmt_10 | asmt_10",
            "Section 73        | asmt_10 | asmt_10",
            "NULL              | rfd_08  | rfd_08",
            "NULL              | NULL    | unknown",
            "Section 16        | NULL    | unknown",
    })
    void detectsStage(String section, String triage, String expected) {
        assertEquals(expected, StageDetector.detect(section, triage));
    }

    @Test
    void countsNumberedNoticeParagraphs() {
        String text = """
                Notice under Section 171
                Reference No: X/1
                1. WHEREAS, a complaint was received.
                2. AND WHEREAS, investigation revealed.
                3. IT IS OBSERVED that prices were raised.
                4. ACCORDINGLY, under Section 171.
                5. NOW THEREFORE, show cause why:
                (a) Rs. 15,00,000/- should not be recovered;
                1. a stray list item that restarts numbering
                6. You are directed to reply within 21 days.
                2022-23 | 85,00,000/- | 65,00,000/-
                """;
        assertEquals(6, NoticeParagraphs.count(text));
        assertEquals(2, NoticeParagraphs.count("Para 1. First.\nPara 2) Second.\n4. skipped"));
        assertEquals(0, NoticeParagraphs.count("no numbered paragraphs here"));
        assertEquals(0, NoticeParagraphs.count(null));
    }

    @Test
    void stripsOnlyATrailingPrayer() {
        String html = "<p>The demand is not sustainable.</p>"
                + "<p>In view of the above, it is prayed that the proceedings be dropped.</p>"
                + "<p>It is further requested that a personal hearing be granted.</p>";
        PrayerStripper.Stripped s = PrayerStripper.stripTrailingPrayer(html);
        assertEquals("<p>The demand is not sustainable.</p>", s.html());
        assertEquals(2, s.removed());

        String headed = "<p>Argument.</p><h3>Prayer</h3><p>Wherefore the Noticee prays for relief.</p>";
        assertEquals("<p>Argument.</p>", PrayerStripper.stripTrailingPrayer(headed).html());

        String requested = "<p>Argument.</p><p>It is therefore requested that the show cause notice be dropped in full.</p>";
        assertEquals(1, PrayerStripper.stripTrailingPrayer(requested).removed());
    }

    @Test
    void keepsArgumentsThatOnlyMentionDroppingOrAreFollowedByMoreText() {
        String conclusion = "<p>In view of the above, the demand is unsustainable and deserves to be dropped.</p>";
        assertEquals(0, PrayerStripper.stripTrailingPrayer(conclusion).removed());

        String midPrayer = "<p>The department prayed for an adjournment earlier.</p><p>The credit is admissible.</p>";
        assertEquals(0, PrayerStripper.stripTrailingPrayer(midPrayer).removed(), "only the tail is a prayer");

        String list = "<ul><li>Ground one.</li><li>It is prayed that the notice be quashed.</li></ul>";
        assertEquals("<ul><li>Ground one.</li></ul>", PrayerStripper.stripTrailingPrayer(list).html());
    }
}
