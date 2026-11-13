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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        when(ik.search(anyString(), eq("zorblax fictional commissioner imaginary"))).thenReturn(List.of());
        var z = agent.verifyAiCitations(List.of(new RawAiCitation("Zorblax Fictional Industries v. Commissioner of Imaginary Taxes", "Supreme Court", 2003, null, "x")),
                UUID.randomUUID()).citations().get(0);
        assertEquals("NOT_FOUND", z.status());
        assertEquals("No judgment with this case name among the 1 hit(s) for \"Zorblax Fictional Industries v. Commissioner of Imaginary Taxes\""
                + " and the 0 hit(s) for \"zorblax fictional commissioner imaginary\"", z.details());
    }

    // ------------------------------------------------------------------ fix 3 (13-11-2026): top hits, second shorter query

    @Test
    void shortQueryIsTheTwoPartiesKeyWords_forLongNamesOnly() {
        assertEquals("bharti airtel union india", CitationVerificationAgent.shortQuery("Bharti Airtel Ltd v. Union of India"));
        assertEquals("bharti airtel union india", CitationVerificationAgent.shortQuery("Bharti Airtel Ltd v. Union of India (2021) 10 SCC 1"));
        assertEquals("hindustan steel state orissa", CitationVerificationAgent.shortQuery("Hindustan Steel Ltd v. State of Orissa"));
        assertEquals("suncraft energy assistant commissioner",
                CitationVerificationAgent.shortQuery("Suncraft Energy Pvt Ltd v. Assistant Commissioner, State Tax"));
        assertEquals("commissioner central brindavan beverages",
                CitationVerificationAgent.shortQuery("Commissioner of Central Excise v. Brindavan Beverages Pvt Ltd"));
        assertEquals("mangalore chemicals deputy commissioner",
                CitationVerificationAgent.shortQuery("Mangalore Chemicals and Fertilizers Ltd v. Deputy Commissioner of Commercial Taxes"));
        // short names (fewer than 7 tokens as searched): no second query
        assertNull(CitationVerificationAgent.shortQuery("Diya Agencies v. State Tax Officer"));
        assertNull(CitationVerificationAgent.shortQuery("Case Four v. State Tax Officer"));
        assertNull(CitationVerificationAgent.shortQuery("Nobody v. Nothing"));
        assertNull(CitationVerificationAgent.shortQuery(null));
        // one party only
        assertNull(CitationVerificationAgent.shortQuery("In re Safari Retreats Private Limited and another application"));
        assertEquals(7, CitationVerificationAgent.LONG_NAME_TOKENS);
    }

    private static List<IkDoc> fiveOtherHits() {
        return List.of(
                new IkDoc("201", "Vodafone Idea Ltd vs Union Of India on 1 March, 2022", "Bombay High Court", "2022-03-01"),
                new IkDoc("202", "Bharti Hexacom Ltd vs Commissioner Of Income Tax on 2 April, 2019", "Delhi High Court", "2019-04-02"),
                new IkDoc("203", "Union Of India vs Reliance Jio Infocomm Ltd on 3 May, 2020", "Supreme Court of India", "2020-05-03"),
                new IkDoc("204", "Bharti Airtel Ltd vs State Of Karnataka on 4 June, 2018", "Karnataka High Court", "2018-06-04"),
                new IkDoc("205", "Tata Teleservices Ltd vs Union Of India on 5 July, 2017", "Delhi High Court", "2017-07-05"));
    }

    private static CitationVerificationAgent agent(IndianKanoonClient ik) {
        AppProperties props = new AppProperties();
        props.getCitation().setIndiankanoonApiToken("tok");
        return new CitationVerificationAgent(new ObjectMapper(), props, null, null, ik);
    }

    @Test
    void theMatchingTitleIsTakenFromAnyOfTheTopHits_notOnlyTheFirst() throws Exception {
        IndianKanoonClient ik = mock(IndianKanoonClient.class);
        List<IkDoc> hits = new java.util.ArrayList<>(fiveOtherHits());
        hits.add(new IkDoc("206", "Union Of India vs Bharti Airtel Ltd. & Ors on 28 October, 2021", "Supreme Court of India", "2021-10-28"));
        when(ik.search(anyString(), eq("Bharti Airtel Ltd v. Union of India"))).thenReturn(hits);

        var result = agent(ik).verifyAiCitations(List.of(new RawAiCitation("Bharti Airtel Ltd v. Union of India", "Supreme Court", 2021, null, "x")),
                UUID.randomUUID());
        var r = result.citations().get(0);

        assertEquals("VERIFIED", r.status(), r.details());
        assertEquals("https://indiankanoon.org/doc/206/", r.sourceUrl());
        assertEquals(6, CitationVerificationAgent.firstMatchingHit("Bharti Airtel Ltd v. Union of India", hits).position());
        assertEquals(1, result.summary().get("ik_calls"), "the first search matched: no second query");
        verify(ik, times(1)).search(anyString(), anyString());
    }

    @Test
    void aLongNameWithNoMatchingTitleGetsOneShorterSecondSearch_countedAsASecondCall() throws Exception {
        IndianKanoonClient ik = mock(IndianKanoonClient.class);
        when(ik.search(anyString(), eq("Bharti Airtel Ltd v. Union of India"))).thenReturn(fiveOtherHits());
        when(ik.search(anyString(), eq("bharti airtel union india"))).thenReturn(List.of(
                new IkDoc("301", "Airtel Payments Bank vs Union Of India on 9 September, 2023", "Delhi High Court", "2023-09-09"),
                new IkDoc("302", "Union Of India vs Bharti Airtel Ltd. & Ors on 28 October, 2021", "Supreme Court of India", "2021-10-28")));

        var result = agent(ik).verifyAiCitations(List.of(new RawAiCitation("Bharti Airtel Ltd v. Union of India", "Supreme Court", 2021, null, "x")),
                UUID.randomUUID());

        assertEquals("VERIFIED", result.citations().get(0).status(), result.citations().get(0).details());
        assertEquals("https://indiankanoon.org/doc/302/", result.citations().get(0).sourceUrl());
        assertEquals(2, result.summary().get("ik_calls"), "the second search is a second priced call");
        verify(ik).search(anyString(), eq("Bharti Airtel Ltd v. Union of India"));
        verify(ik).search(anyString(), eq("bharti airtel union india"));
        verify(ik, times(2)).search(anyString(), anyString());
    }

    @Test
    void noSecondSearchForAShortName_orWhenTheCallCapIsReached() throws Exception {
        IndianKanoonClient ik = mock(IndianKanoonClient.class);
        when(ik.search(anyString(), anyString())).thenReturn(fiveOtherHits());

        // short name: one search, NOT_FOUND names the hit count and the query
        var shortName = agent(ik).verifyAiCitations(List.of(new RawAiCitation("Diya Agencies v. State Tax Officer", "Kerala High Court", 2023, null, "x")),
                UUID.randomUUID());
        assertEquals("NOT_FOUND", shortName.citations().get(0).status());
        assertEquals("No judgment with this case name among the 5 hit(s) for \"Diya Agencies v. State Tax Officer\"", shortName.citations().get(0).details());
        assertEquals(1, shortName.summary().get("ik_calls"));
        verify(ik, times(1)).search(anyString(), anyString());

        // long name, both searches miss: NOT_FOUND names both queries, two calls
        var both = agent(ik).verifyAiCitations(List.of(new RawAiCitation("Hindustan Steel Ltd v. State of Orissa", "Supreme Court", 1969, null, "x")),
                UUID.randomUUID());
        assertEquals("NOT_FOUND", both.citations().get(0).status());
        assertEquals("No judgment with this case name among the 5 hit(s) for \"Hindustan Steel Ltd v. State of Orissa\""
                + " and the 5 hit(s) for \"hindustan steel state orissa\"", both.citations().get(0).details());
        assertEquals(2, both.summary().get("ik_calls"));

        // call cap of 1: the second search is not made and the reason is named
        AppProperties props = new AppProperties();
        props.getCitation().setIndiankanoonApiToken("tok");
        props.getCitation().setMaxCallsPerDraft(1);
        CitationVerificationAgent capped = new CitationVerificationAgent(new ObjectMapper(), props, null, null, ik);
        var cappedResult = capped.verifyAiCitations(List.of(new RawAiCitation("Hindustan Steel Ltd v. State of Orissa", "Supreme Court", 1969, null, "x")),
                UUID.randomUUID());
        assertEquals("NOT_FOUND", cappedResult.citations().get(0).status());
        assertTrue(cappedResult.citations().get(0).details().endsWith("; no second search (IK call cap reached)"), cappedResult.citations().get(0).details());
        assertEquals(1, cappedResult.summary().get("ik_calls"));
    }
}
