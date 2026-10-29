package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IndianKanoonClient.IkDoc;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fix 1 of the citation-only live run (10 of 11 real cases came back NOT_FOUND): the cited case name is matched
 * against the title IndianKanoon prints ("M/S Lgw Industries Limited &amp; Ors vs Union Of India &amp; Ors on 13
 * December, 2021") after both sides are normalised, and the cited court against the docsource string. The titles
 * below are the published titles of reported judgments; no network call is made.
 */
class CitationNameMatchingTest {

    /** cited name -> the IndianKanoon title it must match. */
    private static final String[][] REAL_TITLES = {
            {"LGW Industries Ltd v Union of India", "M/S Lgw Industries Limited & Ors vs Union Of India & Ors on 13 December, 2021"},
            {"Suncraft Energy Pvt Ltd v. Assistant Commissioner, State Tax",
                    "Suncraft Energy Private Limited And Another vs The Assistant Commissioner, State Tax, Ballygunge Charge on 2 August, 2023"},
            {"D.Y. Beathel Enterprises v. State Tax Officer", "M/S.D.Y.Beathel Enterprises vs The State Tax Officer (Data Cell) on 24 February, 2021"},
            {"State of Karnataka v. Ecom Gill Coffee Trading Pvt. Ltd.",
                    "The State Of Karnataka vs M/S Ecom Gill Coffee Trading Private Limited on 13 March, 2023"},
            {"Oryx Fisheries Pvt. Ltd. v. Union of India", "Oryx Fisheries Private Limited vs Union Of India & Ors on 14 October, 2010"},
            {"CCE, Bangalore v. Brindavan Beverages (P) Ltd.", "Commissioner Of Central Excise, Bangalore vs Brindavan Beverages (P) Ltd. & Ors on 6 July, 2007"},
            {"Commissioner of Central Excise v. Brindavan Beverages Pvt Ltd", "Commissioner Of Central Excise, Bangalore vs Brindavan Beverages (P) Ltd. & Ors on 6 July, 2007"},
            {"Arise India Ltd v. Commissioner of Trade & Taxes, Delhi", "Arise India Limited & Ors vs Commissioner Of Trade & Taxes, Delhi & Ors on 26 October, 2017"},
            {"Diya Agencies v. State Tax Officer", "Diya Agencies vs The State Tax Officer on 12 September, 2023"},
            {"Coca Cola India Pvt Ltd v. Commissioner of Central Excise, Pune-III",
                    "Coca Cola India Pvt. Ltd. vs The Commissioner Of Central Excise, Pune-Iii on 25 August, 2009"},
            {"Coca-Cola India Pvt. Ltd. v. CCE, Pune", "Coca Cola India Pvt. Ltd. vs The Commissioner Of Central Excise, Pune-Iii on 25 August, 2009"},
            {"Hindustan Steel Ltd v. State of Orissa", "Hindustan Steel Ltd. vs The State Of Orissa on 4 August, 1969"},
            {"Mangalore Chemicals & Fertilizers Ltd v. Deputy Commissioner of Commercial Taxes",
                    "Mangalore Chemicals And Fertilizers Limited vs Deputy Commissioner Of Commercial Taxes And Others on 1 August, 1991"},
            {"Bharti Airtel Ltd v. Union of India", "Union Of India vs Bharti Airtel Ltd. on 28 October, 2021"},   // parties swapped in appeal
            {"Union of India v. Bharti Airtel Ltd (2021) 10 SCC 1", "Union Of India vs Bharti Airtel Ltd. & Ors on 28 October, 2021"},
    };

    @Test
    void realIndianKanoonTitlesMatchTheCitedNames() {
        for (String[] row : REAL_TITLES) {
            assertTrue(CitationVerificationAgent.namesMatch(row[0], row[1]), "'" + row[0] + "' should match '" + row[1] + "'");
        }
    }

    @Test
    void partyWordsAreNormalisedOnBothSides() {
        assertEquals(List.of(List.of("d", "y", "beathel", "enterprises"), List.of("state", "tax", "officer")),
                CitationVerificationAgent.partyWords("M/S.D.Y.Beathel Enterprises vs The State Tax Officer (Data Cell) on 24 February, 2021"));
        assertEquals(List.of(List.of("commissioner", "central", "excise", "bangalore"), List.of("brindavan", "beverages")),
                CitationVerificationAgent.partyWords("CCE, Bangalore v. Brindavan Beverages (P) Ltd."));
        assertEquals(List.of(List.of("arise", "india"), List.of("commissioner", "trade", "taxes", "delhi")),
                CitationVerificationAgent.partyWords("Arise India Limited & Ors vs Commissioner Of Trade & Taxes, Delhi & Ors on 26 October, 2017"));
        assertEquals(List.of(List.of("air", "india"), List.of("union", "india")),
                CitationVerificationAgent.partyWords("Air India Ltd v. Union of India (2019) 5 SCC 1"), "a petitioner named Air is not a reporter");
    }

    @Test
    void differentRespondentOrInventedNameDoesNotMatch() {
        // the same petitioner against a different respondent is another case
        assertFalse(CitationVerificationAgent.namesMatch("Arise India Ltd v. Commissioner of Trade and Taxes",
                "Arise India Limited vs Union Of India on 3 March, 2019"));
        assertFalse(CitationVerificationAgent.namesMatch("Zorblax Fictional Industries v. Commissioner of Imaginary Taxes",
                "Commissioner Of Income Tax vs Zorbax Industries on 1 January, 2003"));
        assertFalse(CitationVerificationAgent.namesMatch("Zorblax Fictional Industries v. Commissioner of Imaginary Taxes",
                "Some Other Company vs Commissioner Of Central Excise on 1 January, 2003"));
        // the petitioner's key word is mandatory
        assertFalse(CitationVerificationAgent.namesMatch("Suncraft Energy Pvt Ltd v. Assistant Commissioner, State Tax",
                "Moonbeam Energy Private Limited vs The Assistant Commissioner, State Tax on 2 August, 2023"));
        assertFalse(CitationVerificationAgent.namesMatch(null, "x"));
        assertFalse(CitationVerificationAgent.namesMatch("A v. B", null));
    }

    @Test
    void aSecondCaseMergedInBracketsIsIgnoredForSearchAndComparison() {
        String merged = "Commissioner of Sales Tax, Delhi v. Shanti Kiran India Pvt. Ltd. (Arise India Ltd. v. Commissioner of Trade and Taxes, Delhi)";
        assertEquals("Commissioner of Sales Tax, Delhi v. Shanti Kiran India Pvt. Ltd.", CitationVerificationAgent.searchQuery(merged));
        assertTrue(CitationVerificationAgent.namesMatch(merged, "Commissioner Of Sales Tax, Delhi vs Shanti Kiran India Pvt. Ltd. on 2 November, 2012"));
        assertFalse(CitationVerificationAgent.namesMatch(merged, "Arise India Limited & Ors vs Commissioner Of Trade & Taxes, Delhi & Ors on 26 October, 2017"));
        assertEquals("Acme Steel Ltd v. Union of India", CitationVerificationAgent.searchQuery("Acme Steel Ltd v. Union of India"), "a plain name goes as written");
        assertEquals("Union of India v. Bharti Airtel Ltd", CitationVerificationAgent.searchQuery("Union of India v. Bharti Airtel Ltd (2021) 10 SCC 1"),
                "the reporter citation is not part of the search");
    }

    @Test
    void courtMatchesTheIndianKanoonDocsourceStrings() {
        String[][] ok = {
                {"Supreme Court", "Supreme Court of India"}, {"Supreme Court of India", "Supreme Court of India"},
                {"Hon'ble Supreme Court", "Supreme Court of India"}, {"SC", "Supreme Court of India"},
                {"Calcutta High Court", "Calcutta High Court"}, {"High Court of Calcutta", "Calcutta High Court"},
                {"Madras High Court", "Madras High Court"}, {"High Court of Judicature at Madras", "Madras High Court"},
                {"Madras High Court (Madurai Bench)", "Madras High Court"},
                {"Delhi High Court", "Delhi High Court"}, {"High Court of Delhi at New Delhi", "Delhi High Court"},
                {"Kerala High Court", "Kerala High Court"}, {"Bombay High Court", "Bombay High Court"},
                {"High Court of Bombay", "Bombay High Court"}, {"Karnataka High Court", "Karnataka High Court"},
                {"High Court of Karnataka at Bengaluru", "Karnataka High Court"}, {"Delhi HC", "Delhi High Court"},
                {"CESTAT", "CESTAT"}, {"CESTAT, New Delhi", "CESTAT"}, {"CESTAT", "Appellate Tribunal"},
                {"Appellate Tribunal", "CESTAT"}, {"CESTAT, Bangalore", "Customs, Excise and Gold Tribunal - Bangalore"},
                {"Punjab and Haryana High Court", "Punjab-Haryana High Court"},
        };
        for (String[] row : ok) {
            assertTrue(CitationVerificationAgent.courtMatches(row[0], row[1]), row[0] + " should match " + row[1]);
        }
        String[][] no = {
                {"Supreme Court", "Bombay High Court"}, {"Bombay High Court", "Supreme Court of India"},
                {"Madras High Court", "Kerala High Court"}, {"Delhi High Court", "CESTAT"},
                {"ITAT", "CESTAT"}, {"CESTAT", "Income Tax Appellate Tribunal"},
        };
        for (String[] row : no) {
            assertFalse(CitationVerificationAgent.courtMatches(row[0], row[1]), row[0] + " must not match " + row[1]);
        }
        assertFalse(CitationVerificationAgent.courtMatches(null, "Supreme Court of India"));
        assertFalse(CitationVerificationAgent.courtMatches("Supreme Court", ""));
    }

    @Test
    void verificationUsesTheNormalisedMatch_andTheBracketFreeQuery() throws Exception {
        IndianKanoonClient ik = mock(IndianKanoonClient.class);
        AppProperties props = new AppProperties();
        props.getCitation().setIndiankanoonApiToken("tok");
        CitationVerificationAgent agent = new CitationVerificationAgent(new ObjectMapper(), props, null, null, ik);
        when(ik.search(anyString(), eq("LGW Industries Ltd v Union of India"))).thenReturn(List.of(
                new IkDoc("1", "Some Other Petitioner vs Union Of India on 1 January, 2020", "Calcutta High Court", "2020-01-01"),
                new IkDoc("2", "M/S Lgw Industries Limited & Ors vs Union Of India & Ors on 13 December, 2021", "Calcutta High Court", "2021-12-13")));
        var r = agent.verifyAiCitations(List.of(new RawAiCitation("LGW Industries Ltd v Union of India", "Calcutta High Court", 2021, null, "x")),
                UUID.randomUUID()).citations().get(0);
        assertEquals("VERIFIED", r.status(), r.details());
        assertEquals("https://indiankanoon.org/doc/2/", r.sourceUrl());

        String merged = "Commissioner of Sales Tax, Delhi v. Shanti Kiran India Pvt. Ltd. (Arise India Ltd. v. Commissioner of Trade and Taxes, Delhi)";
        when(ik.search(anyString(), eq("Commissioner of Sales Tax, Delhi v. Shanti Kiran India Pvt. Ltd."))).thenReturn(List.of(
                new IkDoc("3", "Commissioner Of Sales Tax, Delhi vs Shanti Kiran India Pvt. Ltd. on 2 November, 2012", "Delhi High Court", "2012-11-02")));
        var m = agent.verifyAiCitations(List.of(new RawAiCitation(merged, "Delhi High Court", 2012, null, "x")), UUID.randomUUID()).citations().get(0);
        assertEquals("VERIFIED", m.status(), m.details());

        when(ik.search(anyString(), eq("Zorblax Fictional Industries v. Commissioner of Imaginary Taxes"))).thenReturn(List.of(
                new IkDoc("4", "Commissioner Of Income Tax vs Zorbax Industries on 1 January, 2003", "Supreme Court of India", "2003-01-01")));
        var z = agent.verifyAiCitations(List.of(new RawAiCitation("Zorblax Fictional Industries v. Commissioner of Imaginary Taxes", "Supreme Court", 2003, null, "x")),
                UUID.randomUUID()).citations().get(0);
        assertEquals("NOT_FOUND", z.status());
    }
}
