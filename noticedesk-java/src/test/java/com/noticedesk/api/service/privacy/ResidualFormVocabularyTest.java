package com.noticedesk.api.service.privacy;

import com.noticedesk.api.testsupport.SyntheticS74Notice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (b43) The anonymiser self-check reported "residual: 1 (caps-phrase-2-words)" on the synthetic Section 74 notice
 * ("BENGALURU SOUTH" of "BENGALURU SOUTH COMMISSIONERATE"), and on another synthetic notice for form words such as
 * "RCM GTA". A phrase made only of GST / legal form vocabulary, or a city followed by a compass word, is not a residual;
 * real names in capitals still are. The anonymiser's own name detection is unchanged.
 */
class ResidualFormVocabularyTest {

    @Test
    void syntheticSection74Notice_anonymised_hasNoResidual() {
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(SyntheticS74Notice.text());
        assertEquals(0, r.residual().total(), r.residual().line() + "\n" + r.text());
        assertFalse(r.text().contains("Vindhya"), "the taxpayer name is replaced");
    }

    @Test
    void formPhrases_areNotResiduals() {
        String text = "NOW THEREFORE the noticee shall pay RCM GTA tax and RCM IGST on OCEAN FREIGHT.\n"
                + "MEIS SCRIPS were sold. RODTEP SCRIPS too. SHOW CAUSE notice. GST BHAVAN, BENGALURU SOUTH COMMISSIONERATE.\n"
                + "JOINT COMMISSIONER OF CENTRAL TAX. ADDITIONAL COMMISSIONER, STATE TAX. MUMBAI CENTRAL DIVISION. CIF FOB.\n";
        NoticeAnonymiser.Residual res = NoticeAnonymiser.residualRisks(text);
        assertEquals(0, res.total(), res.line());
    }

    @Test
    void realNamesInCapitals_areStillResiduals() {
        for (String name : new String[] {"VINDHYA BUILD MART", "GTA LOGISTICS", "MEIS GRANITE TRADERS", "SOUTHERN GRANITE WORKS"}) {
            NoticeAnonymiser.Residual res = NoticeAnonymiser.residualRisks("Supplies were received from " + name + " during the period.");
            assertEquals(1, res.total(), name + " -> " + res.line());
            assertTrue(res.line().contains("caps-phrase"), res.line());
        }
        // and the anonymiser still replaces a name that holds a form word
        String anon = NoticeAnonymiser.anonymise("To,\nM/s GTA LOGISTICS PRIVATE LIMITED\nGSTIN: 29MMMNN9012M1Z7\nPlease reply.").text();
        assertFalse(anon.contains("GTA LOGISTICS"), anon);
    }

    @Test
    void isFormVocabulary() {
        assertTrue(NoticeAnonymiser.isFormVocabulary("RCM GTA"));
        assertTrue(NoticeAnonymiser.isFormVocabulary("NOW THEREFORE"));
        assertTrue(NoticeAnonymiser.isFormVocabulary("BENGALURU SOUTH"));
        assertTrue(NoticeAnonymiser.isFormVocabulary("DELHI WEST II"));
        assertFalse(NoticeAnonymiser.isFormVocabulary("VINDHYA SOUTH"));
        assertFalse(NoticeAnonymiser.isFormVocabulary("GTA LOGISTICS"));
        assertFalse(NoticeAnonymiser.isFormVocabulary(null));
    }
}
