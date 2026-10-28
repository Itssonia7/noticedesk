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
     * The Para 2(c) vendor table as PDFBox reads it: each row on one line, the GSTIN cell wrapped so that its first 7
     * to 9 characters stand after the row number and the remainder starts the next line, about 60 characters away
     * (the three row shapes of a real RFD-08). Leading fragment and remainder are replaced by the same placeholder
     * split at the same position, the joined value is mapped, the same GSTIN split differently twice gets one
     * placeholder, two GSTINs of one State (the same leading placeholder) still round-trip exactly, a fragment that
     * pairs with nothing is replaced by the fragment shape and counted by the self-check, and the layout is unchanged.
     */
    @Test
    void gstinFragmentsARowApartArePairedAndReplaced() {
        String text = """
                GSTIN: 27ZZZZZ9999Z1Z9
                2(c) The following inward supplies are ineligible:
                Sr. GSTIN Trade/Legal Name Invoice value IGST CGST SGST Cess Reason
                1 24YYYYY8 ORBIT TRADERS 12345 0 1111 1111 0 Goods received by the
                888Y1Z8 SUPPLIER after cancellation of
                its registration
                certificate.
                2 29XXXXX77 NOVA GEMS & 234567 0 21111 21111 0 Return not filed by the
                77X1Z7 ASSOCIATES for the period in question
                3 36UUUUU COSMIC DRIP 34567 0 3111 3111 0 Supplier is non-existent at
                4444U1Z4 AND FITTINGS as per field visit
                4 24CCCCC22 LUNAR GOLD AND DIAMONDS 45678 0 4111 4111 0 Invoice not uploaded by the
                22C1Z5 SUPPLIER in GSTR-1
                5 24YYYYY88 ORBIT TRADERS 5678 0 511 511 0 Goods received by the
                88Y1Z8 SUPPLIER after cancellation
                """;
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(text);
        String a = r.text();
        for (String fragment : List.of("ZZZZZ", "YYYYY", "XXXXX", "UUUUU", "CCCCC", "888Y1Z8", "77X1Z7", "4444U1Z4", "22C1Z5", "88Y1Z8")) {
            assertFalse(a.contains(fragment), "survived: " + fragment + "\n" + a);
        }
        assertTrue(a.contains("GSTIN: 27AAAAA0001A1Z1"), a);
        assertTrue(a.contains("\n1 24AAAAA0 ") && a.contains("\n002A1Z2 SUPPLIER after cancellation of\n"), "shape 1: 8 + 7\n" + a);
        assertTrue(a.contains("\n2 29AAAAA00 ") && a.contains("\n03A1Z3 ASSOCIATES for the period"), "shape 2: 9 + 6\n" + a);
        assertTrue(a.contains("\n3 36AAAAA ") && a.contains("\n0004A1Z4 AND "), "shape 3: 7 + 8\n" + a);
        assertTrue(a.contains("\n4 24AAAAA00 ") && a.contains("\n05A1Z5 SUPPLIER in GSTR-1"), "a second GSTIN of the same State\n" + a);
        assertTrue(a.contains("\n5 24AAAAA00 ") && a.contains("\n02A1Z2 SUPPLIER after cancellation\n"), "the same GSTIN split differently: the same placeholder\n" + a);
        assertEquals(text.lines().count(), a.lines().count(), "the line layout is unchanged");
        assertEquals(5, r.map().count(AnonymisationMap.KIND_GSTIN), "five distinct GSTINs: " + r.map().summaryLine());
        assertEquals("24YYYYY8888Y1Z8", r.map().valueOf("24AAAAA0002A1Z2"));
        assertEquals("24CCCCC2222C1Z5", r.map().valueOf("24AAAAA0005A1Z5"));
        // the self-check sees no fragment and no split GSTIN in the anonymised text
        NoticeAnonymiser.Residual residual = r.residual();
        assertFalse(residual.countsByShape().containsKey(NoticeAnonymiser.Residual.SHAPE_GSTIN_LEAD), residual.line());
        assertFalse(residual.countsByShape().containsKey(NoticeAnonymiser.Residual.SHAPE_GSTIN_REMAINDER), residual.line());
        assertFalse(residual.countsByShape().containsKey(NoticeAnonymiser.Residual.SHAPE_SPLIT_GSTIN), residual.line());
        assertFalse(residual.countsByShape().containsKey(NoticeAnonymiser.Residual.SHAPE_GSTIN), residual.line());
        // exact round trip (rows 4 and 5 share the leading placeholder "24AAAAA00": the pair decides), joined form too
        assertEquals(text, NoticeAnonymiser.deAnonymise(a, r.map()));
        assertEquals("suppliers 24YYYYY8888Y1Z8 and 24CCCCC2222C1Z5 and 36UUUUU4444U1Z4",
                NoticeAnonymiser.deAnonymise("suppliers 24AAAAA0002A1Z2 and 24AAAAA0005A1Z5 and 36AAAAA0004A1Z4", r.map()));
        // a second pass changes nothing
        NoticeAnonymiser.Result twice = NoticeAnonymiser.anonymise(a);
        assertEquals(a, twice.text());
        assertTrue(twice.map().isEmpty(), twice.map().summaryLine());
        // a leading fragment whose remainder is not within the next three lines, and a remainder on its own, are still
        // identifying: counted by the self-check, replaced by the fragment shape, restored exactly
        String unpaired = """
                The supplier 24YYYYY8 has not filed its returns.



                888Y1Z8 is the rest of a GSTIN nobody can pair.
                """;
        NoticeAnonymiser.Residual before = NoticeAnonymiser.residualRisks(unpaired);
        assertEquals(1, before.countsByShape().get(NoticeAnonymiser.Residual.SHAPE_GSTIN_LEAD), before.line());
        assertEquals(1, before.countsByShape().get(NoticeAnonymiser.Residual.SHAPE_GSTIN_REMAINDER), before.line());
        assertEquals("residual: 2 (gstin-lead, gstin-remainder)", before.line());
        NoticeAnonymiser.Result u = NoticeAnonymiser.anonymise(unpaired);
        assertFalse(u.text().contains("YYYYY") || u.text().contains("888Y1Z8"), u.text());
        assertTrue(u.text().contains("The supplier 24AAAAA1 has not filed"), u.text());
        assertTrue(u.text().contains("\n002A1Z2 is the rest"), u.text());
        assertEquals(0, u.residual().total(), u.residual().line());
        assertEquals(2, u.map().count(AnonymisationMap.KIND_GSTIN_FRAGMENT), u.map().summaryLine());
        assertEquals(unpaired, NoticeAnonymiser.deAnonymise(u.text(), u.map()));
        assertEquals(u.text(), NoticeAnonymiser.anonymise(u.text()).text(), "a second pass changes nothing");
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
     * Vendor names in the Para 2(c) table as PDFBox reads it (each row one line, the GSTIN wrapped to the next line):
     * the capitals phrase after the row's GSTIN token up to the first amount is the holder's name, every occurrence
     * (a name repeated in a second row, the same name in prose); a name that wraps (its last words right after the
     * GSTIN remainder at the next line's start) is replaced piece by piece ("SUPPLIER 3" / "SUPPLIER 3 CONTD", the
     * trade word stays); "AND" / "&" / "OF" inside a capitals phrase never end it ("LUNAR GOLD AND DIAMONDS" is one
     * name); a capitals form word after the remainder ("SUPPLIER") is not a name; the self-check reports 0 and no
     * capitals phrase of two or more words outside the stop list survives; the round trip is exact.
     */
    @Test
    void vendorNamesInTableRowsReadAsOneLineAreReplaced() {
        String text = """
                GOVERNMENT OF MAHARASHTRA
                OFFICE OF THE DY. COMMISSIONER OF STATE TAX, MUMBAI NODAL DIVISION 3
                FORM GST RFD-08
                To,
                M/s ZETA ALLOYS PRIVATE LIMITED
                GSTIN: 27ZZZZZ9999Z1Z9
                1. M/s Zeta Alloys Private Limited (the claimant) has claimed a refund of INPUT TAX CREDIT on inward supplies.
                2. (a) The supplier of LUNAR GOLD AND DIAMONDS (GSTIN:24CCCCC2222C1Z5) has not filed GSTR-3B.
                   (c) The following inward supplies are ineligible:
                Sr. GSTIN Trade/Legal Name Invoice value IGST CGST SGST Cess Reason
                1 24YYYYY8 ORBIT TRADERS 12345 0 1111 1111 0 Goods received by the
                888Y1Z8 SUPPLIER after cancellation of
                its registration
                certificate.
                2 29XXXXX77 NOVA GEMS & 234567 0 21111 21111 0 Return not filed by the
                77X1Z7 ASSOCIATES for the period in question
                3 36UUUUU COSMIC DRIP 34567 0 3111 3111 0 Supplier is non-existent at
                4444U1Z4 AND FITTINGS as per field visit
                4 24CCCCC22 LUNAR GOLD AND DIAMONDS 45678 0 4111 4111 0 Invoice not uploaded by the
                22C1Z5 SUPPLIER in GSTR-1
                5 24YYYYY88 ORBIT TRADERS 5678 0 511 511 0 Goods received by the
                88Y1Z8 SUPPLIER after cancellation
                TOTAL 102235
                3. The supplier NOVA GEMS & ASSOCIATES is also non-existent; the purchase of GOODS AND SERVICES is denied.
                Dy. Commissioner of State Tax
                """;
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(text);
        String a = r.text();
        String lower = a.toLowerCase(Locale.ROOT);
        for (String name : List.of("zeta alloys", "orbit", "nova gems", "cosmic", "drip", "fittings", "lunar", "gold and diamonds", "diamonds",
                "ZZZZZ", "YYYYY", "XXXXX", "UUUUU", "CCCCC", "888Y1Z8", "77X1Z7", "4444U1Z4", "22C1Z5", "88Y1Z8")) {
            assertFalse(lower.contains(name.toLowerCase(Locale.ROOT)), "survived: " + name + "\n" + a);
        }
        assertTrue(a.contains("M/s NOTICEE COMPANY PRIVATE LIMITED\nGSTIN: 27AAAAA0001A1Z1"), a);
        assertTrue(a.contains("The supplier of SUPPLIER 1 (GSTIN:24AAAAA0002A1Z2) has not filed"), "connectors inside a capitals phrase\n" + a);
        assertTrue(a.contains("\n1 24AAAAA0 SUPPLIER 2 TRADERS 12345 0 1111 1111 0 Goods received by the\n003A1Z3 SUPPLIER after cancellation of\n"),
                "row shape 1: the name up to the first amount, the form word after the remainder stays\n" + a);
        assertTrue(a.contains("\n2 29AAAAA00 SUPPLIER 3 & 234567 0 21111 21111 0 Return not filed by the\n04A1Z4 ASSOCIATES for the period in question\n"),
                "row shape 2: a name that wraps before its trade word\n" + a);
        assertTrue(a.contains("\n3 36AAAAA SUPPLIER 4 34567 0 3111 3111 0 Supplier is non-existent at\n0005A1Z5 AND SUPPLIER 4 CONTD as per field visit\n"),
                "row shape 3: the wrapped words are the rest of the name\n" + a);
        assertTrue(a.contains("\n4 24AAAAA00 SUPPLIER 1 45678 0 4111 4111 0 Invoice not uploaded by the\n02A1Z2 SUPPLIER in GSTR-1\n"),
                "the same name as in prose: the same placeholder\n" + a);
        assertTrue(a.contains("\n5 24AAAAA00 SUPPLIER 2 TRADERS 5678 0 511 511 0 Goods received by the\n03A1Z3 SUPPLIER after cancellation\n"),
                "a name repeated in a second row\n" + a);
        assertTrue(a.contains("The supplier SUPPLIER 3 ASSOCIATES is also non-existent; the purchase of GOODS AND SERVICES is denied."),
                "the wrapped name in one piece in prose; a form phrase with a connector is not a name\n" + a);
        assertFalse(a.contains("NOTICEE COMPANY 2"), "no vendor is mistaken for the taxpayer\n" + a);
        for (String kept : List.of("GOVERNMENT OF MAHARASHTRA", "OFFICE OF THE DY. COMMISSIONER OF STATE TAX, MUMBAI NODAL DIVISION 3",
                "Sr. GSTIN Trade/Legal Name Invoice value IGST CGST SGST Cess Reason", "TOTAL 102235", "Dy. Commissioner of State Tax")) {
            assertTrue(a.contains(kept), "lost: " + kept + "\n" + a);
        }
        assertEquals(text.lines().count(), a.lines().count(), "the line layout is unchanged");
        assertEquals(List.of(), NoticeAnonymiser.upperCaseBusinessPhrases(a), a);
        assertEquals(0, r.residual().total(), r.residual().line() + "\n" + a);
        assertTrue(r.privacyLine().contains("other parties 4") && r.privacyLine().endsWith("residual: 0"), r.privacyLine());
        // exact round trip, the wrapped name in one piece too
        assertEquals(text, NoticeAnonymiser.deAnonymise(a, r.map()));
        assertEquals("reply on NOVA GEMS & ASSOCIATES, LUNAR GOLD AND DIAMONDS and COSMIC DRIP AND FITTINGS",
                NoticeAnonymiser.deAnonymise("reply on SUPPLIER 3 ASSOCIATES, SUPPLIER 1 and SUPPLIER 4 AND SUPPLIER 4 CONTD", r.map()));
        assertEquals("NOVA GEMS", r.map().valueOf("SUPPLIER 3"));
        assertEquals("FITTINGS", r.map().valueOf("SUPPLIER 4 CONTD"));
        assertEquals(a, NoticeAnonymiser.anonymise(a).text(), "a second pass changes nothing");
        // "AND" / "&" / "OF" inside a capitals phrase: one name (a single letter in front carries nothing and is set aside)
        assertEquals(List.of("LUNAR GOLD AND DIAMONDS", "NOVA GEMS & ASSOCIATES", "GOLD AND DIAMONDS"),
                NoticeAnonymiser.upperCaseBusinessPhrases("supplier LUNAR GOLD AND DIAMONDS, NOVA GEMS & ASSOCIATES and X GOLD AND DIAMONDS"));
        assertEquals(List.of(), NoticeAnonymiser.upperCaseBusinessPhrases("GOODS AND SERVICES TAX, OFFICE OF THE COMMISSIONER, REFUND OF INPUT TAX CREDIT"));
    }

    /**
     * The vendor that survived a real RFD-08 after (b30): two consecutive identical table rows whose name holds a
     * single-digit token ("ORBIT 3 LTD") and whose GSTIN's PAN digits start with 0. The digit between capital words is
     * part of the name (an amount has three or more digits), the name maps to "SUPPLIER n" with the legal-form suffix
     * left in the text, every spelling included; the leading fragment pairs with its remainder on the next line and
     * both rows get the same placeholder GSTIN and the same "SUPPLIER n"; a real GSTIN / PAN whose digits start with 0
     * is a value, never "already a placeholder"; a wrapped continuation with a digit inside ("AND FITTINGS 1 INDIA")
     * is the rest of the name; a leading fragment that pairs with nothing is replaced by the fragment shape and the
     * self-check reports 0 afterwards; the round trip is exact and a second pass changes nothing.
     */
    @Test
    void rowNamesWithADigitInsideAndGstinsWithALeadingZeroAreReplaced() {
        String text = """
                GOVERNMENT OF MAHARASHTRA
                FORM GST RFD-08
                To,
                M/s ZETA ALLOYS PRIVATE LIMITED
                GSTIN: 27ZZZZZ9999Z1Z9
                2. (a) The supplier Orbit 3 Ltd has not filed GSTR-3B; the supplier 33WWWWW6 is also under scrutiny.
                   (c) The following inward supplies are ineligible:
                Sr. GSTIN Trade/Legal Name Invoice value IGST CGST SGST Cess Reason
                1 24TTTTT0 ORBIT 3 LTD 12345 1111 0 0 0 Goods received by the
                333T1Z3 after cancellation of
                its registration
                certificate.
                2 24TTTTT0 ORBIT 3 LTD 12345 1111 0 0 0 Goods received by the
                333T1Z3 after cancellation of
                3 36UUUUU COSMIC DRIP 34567 0 3111 3111 0 Supplier is non-existent at
                4444U1Z4 AND FITTINGS 1 INDIA as per field visit
                TOTAL 59257
                Dy. Commissioner of State Tax
                """;
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(text);
        String a = r.text();
        String lower = a.toLowerCase(Locale.ROOT);
        for (String real : List.of("zeta alloys", "orbit", "cosmic", "drip", "fittings", "india", "ZZZZZ", "TTTTT", "UUUUU", "WWWWW",
                "333T1Z3", "4444U1Z4")) {
            assertFalse(lower.contains(real.toLowerCase(Locale.ROOT)), "survived: " + real + "\n" + a);
        }
        assertTrue(a.contains("The supplier Supplier 1 Ltd has not filed GSTR-3B; the supplier 33AAAAA1 is also under scrutiny."),
                "the prose spelling of the name, and a lead that pairs with nothing replaced by the fragment shape\n" + a);
        String row = " 24AAAAA0 SUPPLIER 1 LTD 12345 1111 0 0 0 Goods received by the\n002A1Z2 after cancellation of\n";
        assertTrue(a.contains("\n1" + row), "row 1: the digit is part of the name, the suffix stays, the lead pairs with the next line\n" + a);
        assertTrue(a.contains("\n2" + row), "row 2: the same GSTIN placeholder and the same SUPPLIER n\n" + a);
        assertTrue(a.contains("\n3 36AAAAA SUPPLIER 2 34567 0 3111 3111 0 Supplier is non-existent at\n0003A1Z3 AND SUPPLIER 2 CONTD as per field visit\n"),
                "a wrapped continuation with a digit inside is the rest of the name\n" + a);
        assertEquals(text.lines().count(), a.lines().count(), "the line layout is unchanged");
        assertEquals("ORBIT 3", r.map().valueOf("SUPPLIER 1"));
        assertEquals("Orbit 3", r.map().valueOf("Supplier 1"));
        assertEquals("FITTINGS 1 INDIA", r.map().valueOf("SUPPLIER 2 CONTD"));
        assertEquals("24TTTTT0333T1Z3", r.map().valueOf("24AAAAA0002A1Z2"), "the joined GSTIN is mapped");
        assertEquals("33WWWWW6", r.map().valueOf("33AAAAA1"));
        assertEquals(3, r.map().count(AnonymisationMap.KIND_GSTIN), r.map().summaryLine());
        assertEquals(5, r.map().count(AnonymisationMap.KIND_GSTIN_FRAGMENT), "two leads, two remainders, one unpaired lead: " + r.map().summaryLine());
        assertTrue(r.privacyLine().contains("GSTINs 3") && r.privacyLine().contains("other parties 2") && r.privacyLine().endsWith("residual: 0"), r.privacyLine());
        assertEquals(0, r.residual().total(), r.residual().line() + "\n" + a);
        assertEquals(List.of(), NoticeAnonymiser.upperCaseBusinessPhrases(a), a);
        assertEquals(text, NoticeAnonymiser.deAnonymise(a, r.map()));
        assertEquals("reply on ORBIT 3 LTD and COSMIC DRIP AND FITTINGS 1 INDIA",
                NoticeAnonymiser.deAnonymise("reply on SUPPLIER 1 LTD and SUPPLIER 2 AND SUPPLIER 2 CONTD", r.map()));
        NoticeAnonymiser.Result twice = NoticeAnonymiser.anonymise(a);
        assertEquals(a, twice.text(), "a second pass changes nothing");
        assertTrue(twice.map().isEmpty(), twice.map().summaryLine());
        // the root cause of the surviving lead: a real GSTIN / PAN whose digits start with 0 is not a placeholder
        NoticeAnonymiser.Result zero = NoticeAnonymiser.anonymise("GSTIN 24TTTTT0333T1Z3, PAN TTTTT0333T, GSTIN 24TTTTT0333T1Z3.");
        assertEquals("GSTIN 24AAAAA0001A1Z1, PAN AAAAA0001A, GSTIN 24AAAAA0001A1Z1.", zero.text());
        assertEquals(0, zero.residual().total(), zero.residual().line());
    }

    /**
     * The self-check on rows the anonymiser did not clean: a leading fragment with real letters ("gstin-lead"), a
     * remainder with real characters ("gstin-remainder"), each piece on its own whether paired or not (a lead left
     * next to a placeholder remainder is still a lead), and a capital word on its own between the row's GSTIN token
     * and its first amount ("caps-word-in-row": a one-word vendor name left behind); a counted upper-case phrase is
     * not counted twice, and placeholder, legal-form, trade and form words never count. Never a value in the report.
     */
    @Test
    void residualRisksReportLeadsRemaindersAndLoneCapitalWordsInRows() {
        String rows = """
                1 24TTTTT0 ORBIT 3 LTD 12345 1111 0 0 0 Goods received by the
                333T1Z3 after cancellation of
                2 24TTTTT0 ORBIT 3 LTD 12345 1111 0 0 0 Goods received by the
                333T1Z3 after cancellation of
                """;
        NoticeAnonymiser.Residual r = NoticeAnonymiser.residualRisks(rows);
        assertEquals("residual: 6 (caps-word-in-row x2, gstin-lead x2, gstin-remainder x2)", r.line());
        for (String value : List.of("ORBIT", "TTTTT", "333T1Z3")) {
            assertFalse(r.line().contains(value) || r.shapes().toString().contains(value), "a value leaked into the report: " + value);
        }
        assertEquals("residual: 1 (gstin-lead)",
                NoticeAnonymiser.residualRisks("1 24TTTTT0 SUPPLIER 1 LTD 12345 1111 0 0 0 Goods received by the\n002A1Z2 after cancellation of\n").line(),
                "a real lead next to a placeholder remainder");
        assertEquals("residual: 1 (gstin-remainder)",
                NoticeAnonymiser.residualRisks("1 24AAAAA0 SUPPLIER 1 LTD 12345 1111 0 0 0 Goods received by the\n333T1Z3 after cancellation of\n").line(),
                "a real remainder next to a placeholder lead");
        assertEquals("residual: 0",
                NoticeAnonymiser.residualRisks("1 24AAAAA0 SUPPLIER 1 LTD 12345 1111 0 0 0 Goods received by the\n002A1Z2 after cancellation of\n").line());
        assertEquals("residual: 1 (caps-word-in-row)", NoticeAnonymiser.residualRisks("1 24AAAAA0002A1Z2 ORBIT 12345 0 0 0 Reason").line(),
                "a one-word vendor name after a GSTIN placeholder");
        assertEquals("residual: 1 (caps-word-in-row)", NoticeAnonymiser.residualRisks("1 24AAAAA0 ORBIT 3 LTD 1,00,000.50 0 Reason").line(),
                "an amount with commas and a decimal point ends the name; LTD is a legal-form word");
        assertEquals("residual: 1 (caps-phrase-2-words)", NoticeAnonymiser.residualRisks("1 24AAAAA0002A1Z2 ORBIT TRADERS 12345 0 0 0 Reason").line(),
                "a phrase counted once, not once more per word");
        for (String clean : List.of("1 24AAAAA0002A1Z2 SUPPLIER 2 TRADERS 12345 0 0 0 Reason",
                "1 27AAAAA0001A1Z1 NOTICEE COMPANY PRIVATE LIMITED 12345 0 0 0 Reason",
                "2 29AAAAA00 SUPPLIER 3 & 234567 0 21111 21111 0 Return not filed by the\n04A1Z4 ASSOCIATES for the period",
                "3 36AAAAA SUPPLIER 4 34567 0 3111 3111 0 Supplier is non-existent at\n0005A1Z5 AND SUPPLIER 4 CONTD as per field visit")) {
            assertEquals("residual: 0", NoticeAnonymiser.residualRisks(clean).line(), clean);
        }
    }

    /**
     * The vendor that survived a real RFD-08 after (b31): a name wrapped over THREE cell lines in the row layout. The
     * row holds its first two words, the next line starts with the GSTIN remainder and holds "AND WORD WORD" before
     * the reason text goes on, and the legal suffix "LIMITED" stands at the start of the line after that. The row name
     * and its whole continuation are ONE party: one "SUPPLIER n" (the row's), the continuation "SUPPLIER n CONTD",
     * only the legal suffix kept, never a second placeholder inside the continuation and never a surviving capital
     * word. Also: a continuation of more than four tokens with a digit and "PRIVATE" at its end, a form word inside a
     * row's name ("COBALT SUPPLY AND SERVICES"), the same name in prose with the row's placeholder; the self-check
     * reports 0; the round trip is exact; a second pass changes nothing.
     */
    @Test
    void aNameWrappedOverThreeCellLinesIsOneParty() {
        String text = """
                GOVERNMENT OF MAHARASHTRA
                FORM GST RFD-08
                To,
                M/s ZETA ALLOYS PRIVATE LIMITED
                GSTIN: 27ZZZZZ9999Z1Z9
                2. (c) The following inward supplies are ineligible:
                Sr. GSTIN Trade/Legal Name Invoice value IGST CGST SGST Cess Reason
                8 24YYYYY8 KESTREL HOLLOW 56789 0 5111 5111 0 Return not filed by the
                888Y1Z8 SUPPLIER for the period
                9 29XXXXX7 MARLIN QUARTZ AND 76259 0 1111 1111 0 These invoices do not
                777X1Z7 PEBBLE VANTAGE match in furtherance
                LIMITED of the refund claim.
                10 36UUUUU4 TALON FERRIC 12345 0 1111 1111 0 These invoices do not
                444U1Z4 AND GRANITE HARBOUR match in furtherance
                LIMITED of the refund claim.
                11 24YYYYY8 KESTREL HOLLOW 4321 0 411 411 0 Invoice not uploaded by the
                888Y1Z8 SUPPLIER in GSTR-1
                12 33WWWWW6 COBALT SUPPLY AND 4321 0 411 411 0 Invoice not uploaded by the
                666W1Z6 SERVICES match in furtherance
                LIMITED of the refund claim.
                13 07VVVVV5 ONYX RIDGE 7 12345 0 1111 1111 0 These invoices do not
                555V1Z5 AND SABLE CREST 2 PRIVATE match in furtherance
                LIMITED of the refund claim.
                TOTAL 166280
                3. The supplier Talon Ferric and Granite Harbour Limited, the supplier Marlin Quartz and Pebble Vantage Limited and the supplier Cobalt Supply and Services Limited have not replied.
                Dy. Commissioner of State Tax
                """;
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(text);
        String a = r.text();
        String lower = a.toLowerCase(Locale.ROOT);
        for (String real : List.of("zeta alloys", "kestrel", "hollow", "marlin", "quartz", "pebble", "vantage", "talon", "ferric", "granite", "harbour",
                "cobalt", "onyx", "ridge", "sable", "crest", "ZZZZZ", "YYYYY", "XXXXX", "UUUUU", "WWWWW", "VVVVV", "888Y1Z8", "777X1Z7", "444U1Z4",
                "666W1Z6", "555V1Z5")) {
            assertFalse(lower.contains(real.toLowerCase(Locale.ROOT)), "survived: " + real + "\n" + a);
        }
        assertTrue(a.contains("\n8 24AAAAA0 SUPPLIER 1 56789 0 5111 5111 0 Return not filed by the\n002A1Z2 SUPPLIER for the period\n"),
                "a form word after the remainder is not a continuation\n" + a);
        assertTrue(a.contains("\n9 29AAAAA0 SUPPLIER 2 AND 76259 0 1111 1111 0 These invoices do not\n003A1Z3 SUPPLIER 2 CONTD match in furtherance\nLIMITED of the refund claim.\n"),
                "a name wrapped over three lines with the connector on the row: one party, the legal suffix kept\n" + a);
        assertTrue(a.contains("\n10 36AAAAA0 SUPPLIER 3 12345 0 1111 1111 0 These invoices do not\n004A1Z4 AND SUPPLIER 3 CONTD match in furtherance\nLIMITED of the refund claim.\n"),
                "a name wrapped over three lines with the connector after the remainder: one party, the legal suffix kept\n" + a);
        assertTrue(a.contains("\n11 24AAAAA0 SUPPLIER 1 4321 0 411 411 0 Invoice not uploaded by the\n002A1Z2 SUPPLIER in GSTR-1\n"),
                "the same vendor in a second row\n" + a);
        assertTrue(a.contains("\n12 33AAAAA0 SUPPLIER 4 AND 4321 0 411 411 0 Invoice not uploaded by the\n005A1Z5 SERVICES match in furtherance\nLIMITED of the refund claim.\n"),
                "a form word inside a row's name does not unmake it; the trade word and the legal suffix stay\n" + a);
        assertTrue(a.contains("\n13 07AAAAA0 SUPPLIER 5 7 12345 0 1111 1111 0 These invoices do not\n006A1Z6 AND SUPPLIER 5 CONTD PRIVATE match in furtherance\nLIMITED of the refund claim.\n"),
                "a continuation of five tokens with a digit inside and the legal form split over two lines\n" + a);
        assertTrue(a.contains("The supplier Supplier 3 Limited, the supplier Supplier 2 Limited and the supplier Supplier 4 Services Limited have not replied."),
                "the wrapped names in one piece in prose: the row's placeholder\n" + a);
        assertFalse(a.contains("SUPPLIER 6"), "no second placeholder inside a continuation\n" + a);
        assertEquals(text.lines().count(), a.lines().count(), "the line layout is unchanged");
        assertEquals(List.of(), NoticeAnonymiser.upperCaseBusinessPhrases(a), a);
        assertEquals(0, r.residual().total(), r.residual().line() + "\n" + a);
        assertTrue(r.privacyLine().contains("GSTINs 6") && r.privacyLine().contains("other parties 5") && r.privacyLine().endsWith("residual: 0"), r.privacyLine());
        assertEquals("PEBBLE VANTAGE", r.map().valueOf("SUPPLIER 2 CONTD"));
        assertEquals("GRANITE HARBOUR", r.map().valueOf("SUPPLIER 3 CONTD"));
        assertEquals("SABLE CREST 2", r.map().valueOf("SUPPLIER 5 CONTD"));
        assertEquals("COBALT SUPPLY AND SERVICES", r.map().valueOf("SUPPLIER 4 SERVICES"));
        assertEquals("Marlin Quartz and Pebble Vantage", r.map().valueOf("Supplier 2"));
        assertEquals(text, NoticeAnonymiser.deAnonymise(a, r.map()));
        NoticeAnonymiser.Result twice = NoticeAnonymiser.anonymise(a);
        assertEquals(a, twice.text(), "a second pass changes nothing");
        assertTrue(twice.map().isEmpty(), twice.map().summaryLine());
        // the same shape with the legal form split "PRIVATE" / "LIMITED" over the remainder line and the line after
        String pvt = """
                1 29XXXXX7 MARLIN QUARTZ AND 76259 0 1111 1111 0 These invoices do not
                777X1Z7 PEBBLE VANTAGE PRIVATE match in furtherance
                LIMITED of the refund claim.
                """;
        NoticeAnonymiser.Result p = NoticeAnonymiser.anonymise(pvt);
        assertEquals("""
                1 29AAAAA0 SUPPLIER 1 AND 76259 0 1111 1111 0 These invoices do not
                001A1Z1 SUPPLIER 1 CONTD PRIVATE match in furtherance
                LIMITED of the refund claim.
                """, p.text());
        assertEquals(0, p.residual().total(), p.residual().line());
        assertEquals(pvt, NoticeAnonymiser.deAnonymise(p.text(), p.map()));
    }

    /**
     * The vendor that survived a real RFD-08 after (b32): a trade name that is ONE capital word in the row layout
     * (masked "9 99AAAAA99 AAAAAA 9999999 ..."), with no legal suffix on the row. The single word between the row's
     * GSTIN token and its first amount is the holder's whole name: "SUPPLIER n" without a suffix, every whole-word
     * occurrence replaced case-insensitively ("M/s Orbitex" and "Orbitex" in prose), a common word ("UNITED", which
     * the bare-word rule would otherwise keep until a suffix follows) and a three-letter word included; "M/s Orbitex"
     * near the header GSTIN is no longer taken for the taxpayer, the row's complete GSTIN decides. A legal-form word
     * alone ("LIMITED") is not a party and is left as it is; a one-word name with its suffix on the row ("ORB LTD") is
     * the name plus suffix as before. The self-check reports 0 afterwards; the round trip is exact; a second pass
     * changes nothing.
     */
    @Test
    void aOneWordRowNameIsAParty() {
        String text = """
                GOVERNMENT OF MAHARASHTRA
                FORM GST RFD-08
                To,
                M/s ZETA ALLOYS PRIVATE LIMITED
                GSTIN: 27ZZZZZ9999Z1Z9
                2. (a) The supplier M/s Orbitex has not filed GSTR-3B; the invoices of Orbitex and of United are ineligible.
                   (c) The following inward supplies are ineligible:
                Sr. GSTIN Trade/Legal Name Invoice value IGST CGST SGST Cess Reason
                1 24YYYYY88 ORBITEX 1234567 222222 0 0 0 Goods-1 received by
                88Y1Z8 after cancellation of
                2 29XXXXX77 UNITED 1234567 222222 0 0 0 Goods-1 received by
                77X1Z7 after cancellation of
                3 36UUUUU44 LIMITED 1234567 222222 0 0 0 Goods-1 received by
                44U1Z4 after cancellation of
                4 33WWWWW66 ORB LTD 1234567 222222 0 0 0 Goods-1 received by
                66W1Z6 after cancellation of
                5 07VVVVV55 ZUV 1234567 222222 0 0 0 Goods-1 received by
                55V1Z5 after cancellation of
                TOTAL 6172835
                3. M/s Orb Ltd has not replied; ZUV has not replied either; the unitedly filed replies of ORBITEX are on record.
                Dy. Commissioner of State Tax
                """;
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(text);
        String a = r.text();
        String lower = a.toLowerCase(Locale.ROOT);
        for (String real : List.of("zeta alloys", "orbitex", "united ", "zuv", "orb ltd", "ZZZZZ", "YYYYY", "XXXXX", "UUUUU", "WWWWW", "VVVVV",
                "88Y1Z8", "77X1Z7", "44U1Z4", "66W1Z6", "55V1Z5")) {
            assertFalse(lower.contains(real.toLowerCase(Locale.ROOT)), "survived: " + real + "\n" + a);
        }
        assertTrue(a.contains("The supplier M/s Supplier 1 has not filed GSTR-3B; the invoices of Supplier 1 and of Supplier 2 are ineligible."),
                "the one-word names in prose, title case, \"M/s\" or not: the row's placeholder, no second taxpayer\n" + a);
        assertTrue(a.contains("\n1 24AAAAA00 SUPPLIER 1 1234567 222222 0 0 0 Goods-1 received by\n02A1Z2 after cancellation of\n"),
                "a single capital word between the GSTIN token and the first amount is the whole name\n" + a);
        assertTrue(a.contains("\n2 29AAAAA00 SUPPLIER 2 1234567 222222 0 0 0 Goods-1 received by\n03A1Z3 after cancellation of\n"),
                "a common word alone in that position is a name too\n" + a);
        assertTrue(a.contains("\n3 36AAAAA00 LIMITED 1234567 222222 0 0 0 Goods-1 received by\n04A1Z4 after cancellation of\n"),
                "a legal-form word alone is not a party\n" + a);
        assertTrue(a.contains("\n4 33AAAAA00 SUPPLIER 3 LTD 1234567 222222 0 0 0 Goods-1 received by\n05A1Z5 after cancellation of\n"),
                "a one-word name with its suffix: the existing rule, name plus suffix\n" + a);
        assertTrue(a.contains("\n5 07AAAAA00 SUPPLIER 4 1234567 222222 0 0 0 Goods-1 received by\n06A1Z6 after cancellation of\n"),
                "a three-letter word alone is a name\n" + a);
        assertTrue(a.contains("3. M/s Supplier 3 Ltd has not replied; SUPPLIER 4 has not replied either; the unitedly filed replies of SUPPLIER 1 are on record."),
                "whole words only: \"unitedly\" is not the name; capitals keep the capital placeholder\n" + a);
        assertFalse(a.contains("NOTICEE COMPANY 2") || a.contains("Noticee Company 2"), "no second taxpayer\n" + a);
        assertFalse(a.contains("SUPPLIER 5"), "the legal-form word alone got no placeholder\n" + a);
        assertEquals(text.lines().count(), a.lines().count(), "the line layout is unchanged");
        assertEquals("ORBITEX", r.map().valueOf("SUPPLIER 1"));
        assertEquals("Orbitex", r.map().valueOf("Supplier 1"));
        assertEquals("UNITED", r.map().valueOf("SUPPLIER 2"));
        assertEquals("ORB", r.map().valueOf("SUPPLIER 3"));
        assertEquals("ZUV", r.map().valueOf("SUPPLIER 4"));
        assertEquals(6, r.map().count(AnonymisationMap.KIND_GSTIN), r.map().summaryLine());
        assertTrue(r.privacyLine().contains("other parties 4") && r.privacyLine().endsWith("residual: 0"), r.privacyLine());
        assertEquals(0, r.residual().total(), r.residual().line() + "\n" + a);
        assertEquals(List.of(), NoticeAnonymiser.upperCaseBusinessPhrases(a), a);
        assertEquals(text, NoticeAnonymiser.deAnonymise(a, r.map()));
        NoticeAnonymiser.Result twice = NoticeAnonymiser.anonymise(a);
        assertEquals(a, twice.text(), "a second pass changes nothing");
        assertTrue(twice.map().isEmpty(), twice.map().summaryLine());
        // the same rows un-anonymised: the self-check reports the two one-word names and the three-letter word, never
        // the legal-form word alone (a word of fewer than four letters is below the self-check's threshold)
        String rows = """
                1 24AAAAA0002A1Z2 ORBITEX 1234567 222222 0 0 0 Goods-1 received by
                2 29AAAAA0003A1Z3 UNITED 1234567 222222 0 0 0 Goods-1 received by
                3 36AAAAA0004A1Z4 LIMITED 1234567 222222 0 0 0 Goods-1 received by
                """;
        assertEquals("residual: 2 (caps-word-in-row x2)", NoticeAnonymiser.residualRisks(rows).line());
        // a tail word alone in a row is not a name: nothing is replaced anywhere else in the text
        NoticeAnonymiser.Result india = NoticeAnonymiser.anonymise("GOVERNMENT OF INDIA\n1 24YYYYY8888Y1Z8 INDIA 1234567 0 0 0 Reason\n");
        assertEquals("GOVERNMENT OF INDIA\n1 24AAAAA0001A1Z1 INDIA 1234567 0 0 0 Reason\n", india.text());
    }

    /**
     * The self-check never counts a form phrase or the tail of a placeholder: the legal form, a trade word, "INDIA" or
     * a connector that directly follows a "SUPPLIER n" / "NOTICEE COMPANY" placeholder (or another such word) in a
     * table row or in prose, "PRIVATE LIMITED" on a line of its own, "SHOW CAUSE NOTICE" and officer designations. A
     * capital word that is none of these still counts, after a placeholder too.
     */
    @Test
    void residualRisksNeverCountFormWordsOrTheTailOfAPlaceholder() {
        for (String clean : List.of(
                "1 24AAAAA0 SUPPLIER 9 LTD 12345 1111 0 0 0 Goods received by the\n002A1Z2 after cancellation of",
                "1 24AAAAA0002A1Z2 SUPPLIER 9 LIMITED 12345 0 0 0 Reason",
                "1 24AAAAA0002A1Z2 SUPPLIER 9 PVT LTD 12345 0 0 0 Reason",
                "1 24AAAAA0002A1Z2 SUPPLIER 9 INDIA LIMITED 12345 0 0 0 Reason",
                "1 24AAAAA0002A1Z2 SUPPLIER 9 TRADERS AND CO 12345 0 0 0 Reason",
                "1 27AAAAA0001A1Z1 NOTICEE COMPANY INDIA PRIVATE LIMITED 12345 0 0 0 Reason",
                "9 29AAAAA0 SUPPLIER 2 AND 76259 0 1111 1111 0 These invoices do not\n003A1Z3 SUPPLIER 2 CONTD match in furtherance\nLIMITED of the refund claim.",
                "10 36AAAAA0 SUPPLIER 3 12345 0 1111 1111 0 These invoices do not\n004A1Z4 AND SUPPLIER 3 CONTD match in furtherance\nLIMITED of the refund claim.",
                "The supplier SUPPLIER 9 INDIA LIMITED and M/s SUPPLIER 2 TRADERS AND CO have not replied; SUPPLIER 1 PRIVATE LIMITED is non-existent.",
                "M/s NOTICEE COMPANY\nPRIVATE LIMITED\nGSTIN: 27AAAAA0001A1Z1",
                "SHOW CAUSE NOTICE\nFORM GST RFD-08\nPRIVATE LIMITED\nOFFICE OF THE DY. COMMISSIONER OF STATE TAX\nDY. COMMISSIONER OF STATE TAX\nSTATE TAX OFFICER\n(PROPER OFFICER)\nASSISTANT COMMISSIONER (ST)",
                "SUPPLIER 1 LTD, SUPPLIER 2 LLP, SUPPLIER 3 CORPORATION, SUPPLIER 4 ENTERPRISES, SUPPLIER 5 INDUSTRIES, SUPPLIER 6 CO, SUPPLIER 7 & SONS")) {
            NoticeAnonymiser.Residual r = NoticeAnonymiser.residualRisks(clean);
            assertEquals("residual: 0", r.line(), clean);
        }
        // a genuinely surviving capital word still counts, after a placeholder too
        assertEquals("residual: 1 (caps-word-in-row)", NoticeAnonymiser.residualRisks("1 24AAAAA0002A1Z2 SUPPLIER 1 ORBIT 12345 0 0 0 Reason").line(),
                "a capital word after a placeholder that is not a legal form, trade word or connector");
        assertEquals(1, NoticeAnonymiser.residualRisks("1 24AAAAA0002A1Z2 SUPPLIER 1 LTD ORBIT 12345 0 0 0 Reason").total(),
                "a capital word after the legal suffix of a placeholder");
        assertEquals("residual: 1 (caps-word-in-row)", NoticeAnonymiser.residualRisks("1 24AAAAA0002A1Z2 ORBIT 12345 0 0 0 Reason").line());
        assertEquals("residual: 1 (caps-word-in-row)", NoticeAnonymiser.residualRisks("1 24AAAAA0 ORBIT 3 LTD 1,00,000.50 0 Reason").line());
        assertEquals("residual: 1 (caps-phrase-2-words)", NoticeAnonymiser.residualRisks("1 24AAAAA0002A1Z2 ORBIT TRADERS 12345 0 0 0 Reason").line());
        assertEquals(1, NoticeAnonymiser.residualRisks("The supplier SUPPLIER 1 ORBIT LIMITED has not replied.").total(),
                "an identifying word between a placeholder and its legal suffix in prose");
        assertEquals(1, NoticeAnonymiser.residualRisks("9 29AAAAA0 SUPPLIER 2 AND 76259 0 1111 1111 0 These invoices do not\n003A1Z3 PEBBLE VANTAGE match in furtherance\nLIMITED of").total(),
                "a continuation that survived whole");
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
