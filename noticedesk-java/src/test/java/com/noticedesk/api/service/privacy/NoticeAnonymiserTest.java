package com.noticedesk.api.service.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.service.matching.DefectChecker;
import com.noticedesk.api.service.matching.NoticeHeaderFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The anonymiser on a synthetic show cause notice (fictional party "ZETA ALLOYS", allow-listed placeholder GSTINs):
 * nothing identifying survives, placeholders are consistent and keep their shape, the mapping restores the text
 * exactly, a second pass changes nothing, and the header facts code still reads the taxpayer from the anonymised text.
 */
class NoticeAnonymiserTest {

    static final String TAXPAYER_GSTIN = "27ZZZZZ9999Z1Z9";
    static final String TAXPAYER_PAN = "ZZZZZ9999Z";
    static final String SUPPLIER_A = "24YYYYY8888Y1Z8";
    static final String SUPPLIER_B = "29XXXXX7777X1Z7";

    static final String NOTICE = """
            GOVERNMENT OF MAHARASHTRA
            OFFICE OF THE ASSISTANT COMMISSIONER OF STATE TAX, MUMBAI NODAL DIVISION 3
            FORM GST DRC-01
            Reference No.: ZD270326001234X      Dated: 24.05.2024
            DIN: 20240527XX000000AB12
            To,
            M/s ZETA ALLOYS PRIVATE LIMITED
            Plot No. 12, MIDC Industrial Area, Andheri (East),
            Mumbai, Maharashtra - 400093
            GSTIN: 27ZZZZZ9999Z1Z9
            PAN: ZZZZZ9999Z
            Email: accounts@zetaalloys.example   Mobile: 9876543210

            Sub: Show cause notice under Section 73 of the CGST Act, 2017 for FY 2019-20

            1. M/s Zeta Alloys Private Limited (hereinafter "the taxpayer") has availed ITC of Rs. 4,50,000/- on invoices of the following suppliers:
               (a) M/s Alpha Traders, GSTIN 24YYYYY8888Y1Z8, Vadodara
               (b) M/s Beta Industries Pvt. Ltd., GSTIN 29XXXXX7777X1Z7
            Sr | Supplier | GSTIN | Invoice | Taxable value
            1 | Gamma Enterprises | 24YYYYY8888Y1Z8 | INV/23-24/001 | 1,00,000
            2. The suppliers have not filed GSTR-3B. Shri Rakesh Kumar Sharma, Director of the taxpayer, appeared on 10.06.2024.
            3. ARN AA2703240000011 dated 11.03.2024 was filed late. Interest under Section 50 at 18% on Rs. 4,50,000 (HSN 7206).
            Registered office: 3rd Floor, Zeta House, Marol Co-operative Industrial Estate, Andheri East, Mumbai 400059.
            Bank A/c No. 123456789012 IFSC SBIN0001234.

            Assistant Commissioner of State Tax
            """;

    private static final Pattern GSTIN = Pattern.compile("(?i)(?<![0-9A-Z])\\d{2}[A-Z]{5}\\d{4}[A-Z][0-9A-Z]Z[0-9A-Z](?![0-9A-Z])");

    @Test
    void noRealIdentifierOrNameSurvives() {
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(NOTICE);
        String a = r.text();
        String lower = a.toLowerCase(Locale.ROOT);
        for (String real : List.of(TAXPAYER_GSTIN, TAXPAYER_PAN, SUPPLIER_A, SUPPLIER_B, "ZZZZZ", "YYYYY", "XXXXX",
                "zetaalloys", "9876543210", "ZD270326001234X", "20240527XX000000AB12", "AA2703240000011", "123456789012", "SBIN0001234",
                "Plot No. 12", "Zeta House", "Marol", "400093", "400059")) {
            assertFalse(a.contains(real), "survived: " + real + "\n" + a);
        }
        for (String name : List.of("zeta alloys", "alpha traders", "beta industries", "gamma enterprises", "rakesh", "sharma")) {
            assertFalse(lower.contains(name), "name survived: " + name + "\n" + a);
        }
        // every GSTIN of the anonymised text is a placeholder of the same shape with the state code kept
        Matcher m = GSTIN.matcher(a);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue(m.group().matches("(?:27|24|29)AAAAA000\\d[A-Z]1Z\\d"), m.group());
        }
        assertEquals(4, n, "four GSTIN occurrences stay GSTIN-shaped");
    }

    @Test
    void placeholdersAreConsistentAndKeepTheirShape() {
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(NOTICE);
        String a = r.text();
        assertTrue(a.contains("GSTIN: 27AAAAA0001A1Z1"), a);
        assertTrue(a.contains("PAN: AAAAA0001A"), "the standalone PAN is the PAN inside the taxpayer GSTIN placeholder\n" + a);
        // the supplier GSTIN that appears twice (list and table) has one placeholder; the state codes are kept
        assertEquals(2, count(a, "24AAAAA0002A1Z2"), a);
        assertTrue(a.contains("29AAAAA0003A1Z3"), a);
        // names: the taxpayer keeps its legal-form suffix; other parties keep the trade word and the suffix
        assertTrue(a.contains("M/s NOTICEE COMPANY PRIVATE LIMITED"), a);
        assertTrue(a.contains("M/s Noticee Company Private Limited (hereinafter"), "the second spelling is the same placeholder, re-cased\n" + a);
        assertTrue(a.contains("M/s Supplier 1 Traders, GSTIN 24AAAAA0002A1Z2, Vadodara"), a);
        assertTrue(a.contains("M/s Supplier 2 Industries Pvt. Ltd., GSTIN 29AAAAA0003A1Z3"), a);
        assertTrue(a.contains("| Supplier 3 Enterprises | 24AAAAA0002A1Z2 |"), "a table cell next to another party's GSTIN\n" + a);
        assertTrue(a.contains("Shri Person 1, Director of the taxpayer"), a);
        // addresses: street part replaced, city and State kept, PIN replaced by a PIN-shaped value
        assertTrue(a.contains("\nADDRESS 1,\nMumbai, Maharashtra - 900001\n"), a);
        assertTrue(a.contains("Registered office: ADDRESS 2, Mumbai 900002."), a);
        // references keep their shape (the DIN check still sees a DIN-shaped value), e-mail / phone / bank ids too
        assertTrue(a.contains("Reference No.: ZD000000000001X      Dated: 24.05.2024"), a);
        assertTrue(a.matches("(?s).*DIN: \\d{8}XX\\d{6}AB\\d{2}\\n.*"), a);
        assertTrue(a.contains("ARN AA0000000000003 dated 11.03.2024"), a);
        assertTrue(a.contains("Email: person1@example.com   Mobile: 9000000001"), a);
        assertTrue(a.contains("Bank A/c No. 000000000001 IFSC AAAA0000001."), a);
        // kept: amounts, dates, periods, sections, form names, rates, HSN, invoice numbers, officer designation, office
        for (String kept : List.of("Rs. 4,50,000/-", "24.05.2024", "10.06.2024", "FY 2019-20", "Section 73", "Section 50", "FORM GST DRC-01",
                "18%", "HSN 7206", "INV/23-24/001", "Assistant Commissioner of State Tax", "OFFICE OF THE ASSISTANT COMMISSIONER OF STATE TAX, MUMBAI NODAL DIVISION 3",
                "GSTR-3B", "CGST Act, 2017", "1,00,000")) {
            assertTrue(a.contains(kept), "lost: " + kept + "\n" + a);
        }
        // the privacy line gives counts only
        String line = r.privacyLine();
        assertTrue(line.startsWith("Privacy: anonymised GSTINs 3, PANs 1, names 5 (taxpayer 1, other parties 3, persons 1), addresses 2, references 3"), line);
        assertFalse(line.contains("ZETA"), line);
    }

    @Test
    void theDinCheckStillSeesADinShapedValue() {
        String a = NoticeAnonymiser.anonymise(NOTICE).text();
        Matcher m = Pattern.compile("(?m)^DIN:\\s*(\\S+)$").matcher(a);
        assertTrue(m.find(), a);
        assertTrue(DefectChecker.looksLikeDinValue(m.group(1)), "DIN placeholder " + m.group(1) + " must pass the defect check's DIN value test");
    }

    @Test
    void roundTripRestoresTheOriginalExactly() throws Exception {
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(NOTICE);
        assertEquals(NOTICE, NoticeAnonymiser.deAnonymise(r.text(), r.map()));
        // through the JSON file the runner writes under $PRIVATE_SEED_DIR/results
        ObjectMapper om = new ObjectMapper();
        File f = new File(tmp.toFile(), "n_anon_map.json");
        r.map().writeTo(f, om);
        AnonymisationMap back = AnonymisationMap.readFrom(f, om);
        assertEquals(r.map().entries(), back.entries());
        assertEquals(NOTICE, NoticeAnonymiser.deAnonymise(r.text(), back));
        // a model that re-cases a name placeholder is still restored; text without placeholders is unchanged
        assertEquals("Reply of Zeta Alloys Private Limited to the notice.",
                NoticeAnonymiser.deAnonymise("Reply of Noticee Company Private Limited to the notice.", r.map()));
        assertEquals("plain", NoticeAnonymiser.deAnonymise("plain", r.map()));
        // a longer placeholder wins over a shorter one with the same prefix ("SUPPLIER 1 TRADERS" / "SUPPLIER 1")
        assertEquals(NOTICE, NoticeAnonymiser.deAnonymise(NoticeAnonymiser.anonymise(NOTICE).text(), r.map()));
    }

    @TempDir
    Path tmp;

    @Test
    void anonymisingTwiceChangesNothing() {
        NoticeAnonymiser.Result once = NoticeAnonymiser.anonymise(NOTICE);
        NoticeAnonymiser.Result twice = NoticeAnonymiser.anonymise(once.text());
        assertEquals(once.text(), twice.text());
        assertTrue(twice.map().isEmpty(), "nothing left to replace: " + twice.map().summaryLine());
    }

    @Test
    void headerFactsStillReadTheTaxpayerFromTheAnonymisedText() {
        String a = NoticeAnonymiser.anonymise(NOTICE).text();
        NoticeHeaderFacts.TaxpayerGstin tg = NoticeHeaderFacts.taxpayerGstin(a);
        assertEquals("27AAAAA0001A1Z1", tg.gstin());
        assertTrue(tg.fromParty());
        assertEquals(List.of("24AAAAA0002A1Z2", "29AAAAA0003A1Z3"), tg.otherParties());
        assertEquals("NOTICEE COMPANY PRIVATE LIMITED", NoticeHeaderFacts.taxpayerLegalName(a, tg.gstin()));
        assertEquals("Maharashtra", NoticeHeaderFacts.stateForGstin(tg.gstin()).orElse(null));
        assertEquals(NoticeHeaderFacts.AUTHORITY_STATE, NoticeHeaderFacts.authorityKind(a));
    }

    @Test
    void personsLabelledNamesAndOtherIdsAreReplaced() {
        String text = """
                Name of the taxpayer: Orbit Lamps LLP
                Trade Name: Orbit Lighting
                GSTIN 27ZZZZZ9999Z1Z9  TAN MUMO12345A  CIN U12345MH2010PTC123456
                IEC: 0312345678  Aadhaar No: 1234 5678 9012
                Name of the supplier: Delta Agencies
                Proprietor: Mohan Lal Verma, Authorised Signatory - Ms. Priya Nair
                Shri Ram Traders supplied goods. Mr. Verma signed the invoice. Contact +91 98765 43210, 022-23456789.
                Address of the supplier: Shop 4, Lotus Market, Vapi, Gujarat, PIN 396195
                """;
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(text);
        String a = r.text();
        for (String real : List.of("Orbit Lamps", "Orbit Lighting", "MUMO12345A", "U12345MH2010PTC123456", "0312345678", "1234 5678 9012",
                "Delta Agencies", "Mohan Lal Verma", "Priya Nair", "Ram Traders", "Lotus Market", "396195")) {
            assertFalse(a.contains(real), "survived: " + real + "\n" + a);
        }
        assertTrue(a.contains("Name of the taxpayer: Noticee Company LLP"), a);
        assertTrue(a.contains("Trade Name: Noticee Company 2"), "a second taxpayer name gets its own placeholder\n" + a);
        assertTrue(a.contains("Name of the supplier: Supplier 1 Agencies"), a);
        assertTrue(a.contains("Proprietor: Person 1, Authorised Signatory - Ms. Person 2"), a);
        assertTrue(a.contains("Shri Supplier 2 Traders supplied goods"), "a business after an honorific is another party\n" + a);
        assertTrue(a.contains("Mr. Person 3 signed"), a);
        assertTrue(a.contains("Contact +91 90000 00001, 002-00000002."), "phone numbers keep their shape\n" + a);
        assertTrue(a.contains("TAN AAAA00001A"), a);
        assertTrue(a.contains("CIN U00000AA0000AAA000001"), a);
        assertTrue(a.contains("Aadhaar No: 9000 0000 0001"), a);
        assertTrue(a.contains("Address of the supplier: ADDRESS 1, Vapi, Gujarat, PIN 900001"), a);
        assertEquals(text, NoticeAnonymiser.deAnonymise(a, r.map()));
    }

    /**
     * A PDF text layer breaks the GSTINs of a vendor table in two (the first 8 characters, a line end, the last 7) or
     * a hyphen is printed inside one: each is replaced by the placeholder written with the same break, the joined
     * value is mapped too, the taxpayer's split occurrence gets the taxpayer's placeholder, and the round trip is exact.
     */
    @Test
    void gstinsSplitOverALineBreakOrAHyphenAreReplaced() {
        String text = """
                GSTIN: 27ZZZZZ9999Z1Z9
                Para 2(c): the following inward supplies
                Sr. GSTIN of supplier Invoice value
                1 24YYYYY8
                888Y1Z8 1,00,000
                2 29XXXXX7-777X1Z7 2,00,000
                3 27ZZZZZ9
                999Z1Z9 3,00,000
                4 24YYYYY8
                   888Y1Z8 4,00,000
                """;
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(text);
        String a = r.text();
        for (String fragment : List.of("ZZZZZ", "YYYYY", "XXXXX", "999Z1Z9", "888Y1Z8", "777X1Z7")) {
            assertFalse(a.contains(fragment), "survived: " + fragment + "\n" + a);
        }
        assertTrue(a.contains("GSTIN: 27AAAAA0001A1Z1"), a);
        assertTrue(a.contains("1 24AAAAA0\n002A1Z2 1,00,000"), "the placeholder keeps the line break\n" + a);
        assertTrue(a.contains("2 29AAAAA0-003A1Z3 2,00,000"), "the placeholder keeps the hyphen\n" + a);
        assertTrue(a.contains("3 27AAAAA0\n001A1Z1 3,00,000"), "the taxpayer's split occurrence is the taxpayer's placeholder\n" + a);
        assertTrue(a.contains("4 24AAAAA0\n   002A1Z2 4,00,000"), "the same vendor twice: the same placeholder, indentation kept\n" + a);
        assertEquals(text.length(), a.length(), "the layout (line count, breaks) is unchanged");
        // exact round trip; the joined form restores too (a model may join the halves in its output)
        assertEquals(text, NoticeAnonymiser.deAnonymise(a, r.map()));
        assertEquals("24YYYYY8888Y1Z8", r.map().valueOf("24AAAAA0002A1Z2"));
        assertEquals("the supplier 24YYYYY8888Y1Z8 and 29XXXXX7777X1Z7",
                NoticeAnonymiser.deAnonymise("the supplier 24AAAAA0002A1Z2 and 29AAAAA0003A1Z3", r.map()));
        assertEquals(3, r.map().count(AnonymisationMap.KIND_GSTIN), "three distinct GSTINs, split or whole: " + r.map().summaryLine());
        // a second pass changes nothing
        NoticeAnonymiser.Result twice = NoticeAnonymiser.anonymise(a);
        assertEquals(a, twice.text());
        assertTrue(twice.map().isEmpty(), twice.map().summaryLine());
        // a contiguous GSTIN placeholder is still what the header facts read
        assertEquals("27AAAAA0001A1Z1", NoticeHeaderFacts.taxpayerGstin(a).gstin());
    }

    /**
     * Vendor names a refund show cause notice prints in capitals, in three layouts: (a) prose, the name before a
     * bracketed "(GSTIN:...)"; (b) "supplier i.e. NAME" with the GSTIN on the next line and "supplier of NAME";
     * (c) a table whose cells are each on their own line (split GSTIN, blank line, the name, blank line, amounts),
     * the same vendor twice. Every one becomes "SUPPLIER n" (trade word kept), every occurrence, the taxpayer stays
     * "NOTICEE COMPANY", form / office / heading words in capitals are untouched, and no upper-case business phrase
     * survives.
     */
    @Test
    void upperCaseVendorNamesInProseAndTableBlocksAreReplaced() {
        String text = """
                GOVERNMENT OF MAHARASHTRA
                OFFICE OF THE DY. COMMISSIONER OF STATE TAX, MUMBAI NODAL DIVISION 3
                FORM GST RFD-08
                SHOW CAUSE NOTICE FOR REJECTION OF REFUND APPLICATION
                To,
                M/s ZETA ALLOYS PRIVATE LIMITED
                GSTIN: 27ZZZZZ9999Z1Z9
                1. M/s Zeta Alloys Private Limited (the claimant) has claimed a refund of INPUT TAX CREDIT on inward supplies.
                2. (a) On verification it is seen that the supplier  KAPPA WIRELESS NETWORKS  (GSTIN:24YYYYY8888Y1Z8)  at Rs. 2,00,000 has not filed GSTR-3B.
                   (b) The supplier i.e.  SIGMA FREIGHT MOVERS
                (GSTIN:  29XXXXX7777X1Z7) is non-existent; the supplier  of  THETA METAL TRADERS  (GSTIN:36UUUUU4444U1Z4) is cancelled.
                   (c) The following inward supplies are ineligible:
                Sr. No.
                GSTIN of supplier
                Trade/Legal Name
                Invoice value
                IGST
                Reason
                1
                33WWWWW6
                666W1Z6

                OMICRON CABLES LIMITED

                5,00,000
                90,000
                Supplier has not filed GSTR-1
                2
                07VVVVV5
                555V1Z5

                UPSILON POLYMERS PVT. LTD.

                3,00,000
                54,000
                Supplier registration cancelled
                3
                33WWWWW6
                666W1Z6

                OMICRON CABLES LIMITED

                1,00,000
                18,000
                TOTAL
                9,00,000
                3. The claimant is directed to show cause why the refund of Rs. 1,62,000 should not be rejected under Section 54 of the CGST Act, 2017.
                Dy. Commissioner of State Tax
                """;
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(text);
        String a = r.text();
        String lower = a.toLowerCase(Locale.ROOT);
        for (String name : List.of("zeta alloys", "kappa wireless", "sigma freight", "theta metal", "omicron cables", "upsilon polymers",
                "ZZZZZ", "YYYYY", "XXXXX", "UUUUU", "WWWWW", "VVVVV")) {
            assertFalse(lower.contains(name.toLowerCase(Locale.ROOT)), "survived: " + name + "\n" + a);
        }
        assertTrue(a.contains("M/s NOTICEE COMPANY PRIVATE LIMITED\nGSTIN: 27AAAAA0001A1Z1"), a);
        assertTrue(a.contains("M/s Noticee Company Private Limited (the claimant)"), a);
        assertTrue(a.contains("the supplier  SUPPLIER 1  (GSTIN:24AAAAA0002A1Z2)  at Rs. 2,00,000"), "layout (a)\n" + a);
        assertTrue(a.contains("supplier i.e.  SUPPLIER 2\n(GSTIN:  29AAAAA0003A1Z3)"), "layout (b), GSTIN on the next line\n" + a);
        assertTrue(a.contains("supplier  of  SUPPLIER 3 TRADERS  (GSTIN:36AAAAA0004A1Z4)"), "layout (b), trade word kept\n" + a);
        assertEquals(2, count(a, "\n33AAAAA0\n005A1Z5\n\nSUPPLIER 4 LIMITED\n"), "layout (c): the same vendor twice, the same placeholder\n" + a);
        assertTrue(a.contains("\n07AAAAA0\n006A1Z6\n\nSUPPLIER 5 POLYMERS PVT. LTD.\n"), "layout (c): trade word and legal-form suffix kept\n" + a);
        assertFalse(a.contains("NOTICEE COMPANY 2"), "no vendor is mistaken for the taxpayer\n" + a);
        // form, office and heading words in capitals stay
        for (String kept : List.of("GOVERNMENT OF MAHARASHTRA", "OFFICE OF THE DY. COMMISSIONER OF STATE TAX, MUMBAI NODAL DIVISION 3",
                "FORM GST RFD-08", "SHOW CAUSE NOTICE FOR REJECTION OF REFUND APPLICATION", "INPUT TAX CREDIT", "Trade/Legal Name",
                "GSTIN of supplier", "TOTAL\n9,00,000", "Supplier has not filed GSTR-1", "Supplier registration cancelled",
                "Section 54 of the CGST Act, 2017", "Dy. Commissioner of State Tax")) {
            assertTrue(a.contains(kept), "lost: " + kept + "\n" + a);
        }
        // no upper-case phrase of two or more words outside the stop list survives
        assertEquals(List.of(), NoticeAnonymiser.upperCaseBusinessPhrases(a), a);
        assertTrue(r.privacyLine().contains("other parties 5"), r.privacyLine());
        assertEquals(text, NoticeAnonymiser.deAnonymise(a, r.map()));
        assertEquals(a, NoticeAnonymiser.anonymise(a).text(), "a second pass changes nothing");
    }

    /**
     * The signature block at the end: the signing officer's name on a line of its own next to the designation line,
     * after "Sd/-", in brackets, or before a designation on one line ("..., State Tax Officer") -> "PERSON n"; the
     * designation, the office line, place and date stay.
     */
    @Test
    void personNamesInTheSignatureBlockAreReplaced() {
        String text = """
                GSTIN: 27ZZZZZ9999Z1Z9
                3. You are directed to file a reply within 15 days of receipt of this notice.
                Place: Mumbai
                Date: 12.09.2025

                Sd/-
                Nikhil Raut
                Dy. Commissioner of State Tax
                Mumbai Nodal Division 3

                Copy to: Sunita Gaikwad, State Tax Officer, for information and necessary action.
                Signed by Nikhil Raut on 12.09.2025.

                (Prakash Shelar)
                State Tax Officer
                Digitally signed by
                Prakash Shelar
                """;
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(text);
        String a = r.text();
        for (String name : List.of("Nikhil", "Raut", "Sunita", "Gaikwad", "Prakash", "Shelar")) {
            assertFalse(a.contains(name), "survived: " + name + "\n" + a);
        }
        assertTrue(a.contains("\nSd/-\nPerson 1\nDy. Commissioner of State Tax\nMumbai Nodal Division 3\n"), a);
        assertTrue(a.contains("Copy to: Person 2, State Tax Officer, for information"), a);
        assertTrue(a.contains("Signed by Person 1 on 12.09.2025."), "every occurrence of the same person\n" + a);
        assertTrue(a.contains("\n(Person 3)\nState Tax Officer\nDigitally signed by\nPerson 3\n"), a);
        for (String kept : List.of("Place: Mumbai", "Date: 12.09.2025", "within 15 days")) assertTrue(a.contains(kept), "lost: " + kept + "\n" + a);
        assertTrue(r.privacyLine().contains("persons 3"), r.privacyLine());
        assertEquals(text, NoticeAnonymiser.deAnonymise(a, r.map()));
        assertEquals(a, NoticeAnonymiser.anonymise(a).text(), "a second pass changes nothing");
        // the existing notice's closing designation line (no name) is untouched
        assertTrue(NoticeAnonymiser.anonymise(NOTICE).text().endsWith("\nAssistant Commissioner of State Tax\n"));
    }

    @Test
    void emptyAndPlainTextAreUntouched() {
        assertEquals("", NoticeAnonymiser.anonymise("").text());
        assertEquals("", NoticeAnonymiser.anonymise(null).text());
        String plain = "Show cause notice under Section 74 for FY 2021-22: ITC of Rs. 12,000 proposed to be denied under Section 17(5).";
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(plain);
        assertEquals(plain, r.text());
        assertTrue(r.map().isEmpty());
        assertEquals("Privacy: anonymised GSTINs 0, PANs 0, names 0 (taxpayer 0, other parties 0, persons 0), addresses 0, references 0, other ids 0, residual: 0",
                r.privacyLine());
    }

    /**
     * The self-check after anonymisation: a clean anonymised text gives residual 0 (placeholders never count); a text
     * with a GSTIN, a split GSTIN, a PAN, an e-mail, a mobile number and an upper-case business phrase left in it is
     * counted by shape, and the report never carries a value.
     */
    @Test
    void residualRisksCountWhatStillLooksLikeAValueByShapeOnly() {
        for (String clean : List.of(NoticeAnonymiser.anonymise(NOTICE).text(), NoticeAnonymiser.anonymise("").text(),
                "Show cause notice under Section 74: ITC of Rs. 9876543210 denied; SUPPLIER 1 TRADERS and NOTICEE COMPANY PRIVATE LIMITED, "
                        + "GSTIN 27AAAAA0001A1Z1, PAN AAAAA0001A, person1@example.com, 9000000001, GOVERNMENT OF MAHARASHTRA, INPUT TAX CREDIT.")) {
            NoticeAnonymiser.Residual r = NoticeAnonymiser.residualRisks(clean);
            assertEquals(0, r.total(), r.line() + "\n" + clean);
            assertEquals("residual: 0", r.line());
        }
        assertTrue(NoticeAnonymiser.anonymise(NOTICE).privacyLine().endsWith(", residual: 0"), NoticeAnonymiser.anonymise(NOTICE).privacyLine());
        String dirty = """
                supplier KAPPA WIRELESS NETWORKS with GSTIN 24YYYYY8888Y1Z8 and 29XXXXX7
                777X1Z7, PAN ZZZZZ9999Z, e-mail accounts@kappa.example, mobile 9876543210, amount Rs. 9123456789.
                """;
        NoticeAnonymiser.Residual r = NoticeAnonymiser.residualRisks(dirty);
        assertEquals(6, r.total(), r.line());
        assertEquals(1, r.countsByShape().get(NoticeAnonymiser.Residual.SHAPE_GSTIN));
        assertEquals(1, r.countsByShape().get(NoticeAnonymiser.Residual.SHAPE_SPLIT_GSTIN));
        assertEquals(1, r.countsByShape().get(NoticeAnonymiser.Residual.SHAPE_PAN));
        assertEquals(1, r.countsByShape().get(NoticeAnonymiser.Residual.SHAPE_EMAIL));
        assertEquals(1, r.countsByShape().get(NoticeAnonymiser.Residual.SHAPE_PHONE), "the amount after Rs. is not a phone");
        assertEquals(1, r.countsByShape().get("caps-phrase-3-words"));
        assertEquals("residual: 6 (caps-phrase-3-words, email, gstin, gstin-split, pan, phone-10-digits)", r.line());
        for (String value : List.of("KAPPA", "YYYYY", "XXXXX", "ZZZZZ", "kappa", "9876543210")) {
            assertFalse(r.line().contains(value) || r.shapes().toString().contains(value), "a value leaked into the report: " + value);
        }
        // the anonymiser itself leaves nothing of that text behind
        assertEquals(0, NoticeAnonymiser.anonymise(dirty).residual().total(), NoticeAnonymiser.anonymise(dirty).text());
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }
}
