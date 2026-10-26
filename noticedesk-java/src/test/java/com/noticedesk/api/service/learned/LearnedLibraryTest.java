package com.noticedesk.api.service.learned;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.matching.TemplateFillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Learned library: file round trip, separation from the catalogue, approval and reuse. No database, no model call. */
class LearnedLibraryTest {

    @TempDir
    Path privateDir;

    private final ObjectMapper om = new ObjectMapper();
    private AppProperties props;
    private LearnedLibrary library;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.setPrivateSeedDir(privateDir.toString());
        library = new LearnedLibrary(props, om);
        library.setSourceRun("abc1234def");
        library.setClock(Clock.fixed(Instant.parse("2026-10-26T10:15:30Z"), ZoneOffset.UTC));
    }

    private static MatchedIssue noneIssue(int no) {
        return new MatchedIssue(no, "none", List.of(), "ITC on machinery hire charges denied as a blocked credit", List.of(),
                Map.of("period", "2022-23", "amount", 250000.0, "legal_section", "Section 17(5)",
                        "ground", "ITC on machinery hire charges treated as blocked credit"),
                List.of("4"), List.of());
    }

    private static Map<String, Object> aiOutput() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("writer", "new_issue");
        out.put("sections", List.of(
                Map.of("section", 4, "html", "<p>The hire of machinery is not a works contract service. Section 17(5)(d) does not apply to "
                        + "Test Client Pvt Ltd. The demand of Rs 2,50,000 for 2022-23 is unfounded.</p>"),
                Map.of("section", 5, "html", "<li><strong>Para 4:</strong> Denied.</li>"),
                Map.of("section", 6, "html", "<p>Section 16(2) conditions are met. See Beta Ltd v. Commissioner of CGST.</p>")));
        out.put("citations", List.of(Map.of("case", "Beta Ltd v. Commissioner of CGST", "court", "Supreme Court", "year", 2020,
                "cited_for", "burden on revenue", "check_status", "VERIFIED")));
        out.put("documents", List.of("Hire agreements", "Invoices of the hire charges"));
        out.put("summary_line", "ITC on machinery hire is not blocked.");
        return out;
    }

    private LearnedLibrary.RunContext run() {
        return new LearnedLibrary.RunContext("Notice_07 Partial", "DRC-01", "reply", "scn_73",
                Map.of("client_name", "Test Client Pvt Ltd", "notice_number", "REF/1"));
    }

    private LearnedEntry saveOne() {
        return library.record(run(), noneIssue(3), aiOutput(), "gemini:test-model", "new_issue_v2").orElseThrow();
    }

    @Test
    void writeReadRoundTrip() throws Exception {
        LearnedEntry saved = saveOne();
        assertTrue(saved.entryId().matches("LRN-[0-9a-f]{8}"), saved.entryId());

        File file = new File(privateDir.toFile(), "learned/2026-10-26_notice-07-partial_issue3.json");
        assertTrue(file.isFile(), "file named <date>_<notice-id>_issue<N>.json");
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        for (String key : List.of("\"entry_id\"", "\"notice_id\"", "\"created_at\"", "\"document_type\"", "\"stage\"", "\"ground\"",
                "\"legal_section\"", "\"amount\"", "\"period\"", "\"grounds_of_defence\"", "\"legal_submissions\"", "\"documents\"",
                "\"citations\"", "\"writer\"", "\"status\"", "\"approved_by\"", "\"source_run\"")) {
            assertTrue(json.contains(key), "missing " + key);
        }
        assertTrue(json.contains("\"approved_by\" : null"), "approved_by written as null");

        LearnedLibrary fresh = new LearnedLibrary(props, om);
        LearnedLibrary.Counts c = fresh.load();
        assertEquals("learned library: 1 entries (1 provisional, 0 approved)", c.line());
        LearnedEntry back = fresh.entries().get(0);
        assertEquals(saved, back);
        assertEquals("Notice_07 Partial", back.noticeId());
        assertEquals(3, back.issueNo());
        assertEquals("DRC-01", back.documentType());
        assertEquals("reply", back.stage());
        assertEquals("ITC on machinery hire charges treated as blocked credit", back.ground());
        assertEquals("Section 17(5)", back.legalSection());
        assertEquals(250000.0, ((Number) back.amount()).doubleValue());
        assertEquals("2022-23", back.period());
        assertTrue(back.groundsOfDefence().contains("not a works contract service"));
        assertTrue(back.legalSubmissions().contains("Section 16(2)"));
        assertFalse(back.groundsOfDefence().contains("Para 4"), "para-wise text is not a ground of defence");
        assertEquals(List.of("Hire agreements", "Invoices of the hire charges"), back.documents());
        assertEquals("VERIFIED", back.citations().get(0).get("check_status"));
        assertEquals("gemini:test-model", back.writer());
        assertEquals("provisional", back.status());
        assertNull(back.approvedBy());
        assertEquals("abc1234def", back.sourceRun());
    }

    @Test
    void nothingWrittenWithoutPrivateSeedDir() {
        props.setPrivateSeedDir("");
        assertTrue(library.record(run(), noneIssue(1), aiOutput(), "x:y", "v").isEmpty());
        assertNull(library.dir());
        assertEquals(0, library.counts().total());
    }

    @Test
    void neverWritesInsideAGitWorkTree() throws Exception {
        Path repo = privateDir.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        props.setPrivateSeedDir(repo.toString());
        assertTrue(library.record(run(), noneIssue(1), aiOutput(), "x:y", "v").isEmpty());
        assertFalse(Files.exists(repo.resolve("learned")));
    }

    @Test
    void provisionalEntriesNeverReachTheMatcher() {
        saveOne();
        props.getCatalogue().setIncludeLearned(true);
        CatalogueService catalogue = new CatalogueService(null, props, om);
        catalogue.setLearnedLibrary(library);
        assertEquals(List.of(), catalogue.getActiveCards(), "learned entries are never part of the catalogue");
        assertEquals(List.of(), catalogue.learnedCardsForMatching(), "a provisional entry is not shown even with the flag on");
        assertTrue(catalogue.cardsById().isEmpty());
        assertTrue(library.templateFor(library.entries().get(0).entryId()).isEmpty());
    }

    @Test
    void approvedEntriesReachTheMatcherOnlyWithTheFlag() {
        LearnedEntry saved = saveOne();
        library.approve(saved.entryId(), "CA Reviewer");
        CatalogueService catalogue = new CatalogueService(null, props, om);
        catalogue.setLearnedLibrary(library);

        assertEquals(List.of(), catalogue.learnedCardsForMatching(), "include-learned defaults to false");

        props.getCatalogue().setIncludeLearned(true);
        List<IssueCard> cards = catalogue.learnedCardsForMatching();
        assertEquals(1, cards.size());
        assertEquals(List.of(), catalogue.getActiveCards(), "still not part of getActiveCards()");
        IssueCard card = cards.get(0);
        assertEquals(saved.entryId(), card.cardId());
        assertEquals("ITC on machinery hire charges treated as blocked credit", card.title());
        assertTrue(card.summary().contains("approved by CA Reviewer"));
        assertTrue(card.summary().contains("The hire of machinery is not a works contract service."), card.summary());
        assertEquals(List.of("scn_73"), card.stages());
        assertEquals(List.of("Section 17(5)", "Section 17(5)(d)", "Section 16(2)"), card.sections());
        assertEquals(List.of("section 17(5)", "section 17(5)(d)", "section 16(2)"), card.keywords());
        assertEquals("2022-04-01", card.periodFrom());
        assertTrue(catalogue.cardsById().containsKey(saved.entryId()));

        // the matcher block renders it like any card
        String text = catalogue.renderShortlistText(CatalogueService.withLearned(List.of(), cards), List.of());
        assertTrue(text.contains("CARD_ID: " + saved.entryId()));
    }

    @Test
    void approvedEntryIsATemplateOfItsOwnWithNoticeValuesReplaced() {
        LearnedEntry saved = saveOne();
        library.approve(saved.entryId(), "CA Reviewer");
        props.getCatalogue().setIncludeLearned(true);
        TemplateFillService fill = new TemplateFillService(null, props, om);
        fill.setLearnedLibrary(library);

        ReplyTemplate t = fill.selectTemplateForCardAndPeriod(saved.entryId(), "2023-24");
        assertNotNull(t, "the approved entry's text is a template");
        assertEquals("TPL-" + saved.entryId(), t.templateId());
        assertEquals(TemplateFillService.STATUS_PROVISIONAL, t.status());
        String all = t.blocks().stream().map(TemplateBlock::html).reduce("", String::concat);
        assertTrue(all.contains("not a works contract service"));
        assertFalse(all.contains("Test Client Pvt Ltd"), "client name replaced by a placeholder");
        assertFalse(all.contains("2,50,000"), "amount replaced by a placeholder");
        assertTrue(t.blocks().stream().noneMatch(b -> b.section() == 5), "para-wise replies are notice-specific");
        assertEquals(List.of("Hire agreements", "Invoices of the hire charges"), t.documents());

        props.getCatalogue().setIncludeLearned(false);
        assertNull(fill.selectTemplateForCardAndPeriod(saved.entryId(), "2023-24"), "no template with the flag off");
    }

    @Test
    void reRunReplacesAProvisionalEntryButNeverAnApprovedOne() {
        LearnedEntry first = saveOne();
        LearnedEntry second = saveOne();
        assertEquals(first.entryId(), second.entryId(), "same day, same notice and issue: the provisional file is replaced");
        assertEquals(1, library.counts().total());

        library.approve(first.entryId(), "CA Reviewer");
        LearnedEntry third = saveOne();
        assertNotEquals(first.entryId(), third.entryId());
        assertTrue(new File(privateDir.toFile(), "learned/2026-10-26_notice-07-partial_issue3_2.json").isFile());
        LearnedLibrary fresh = new LearnedLibrary(props, om);
        assertEquals("learned library: 2 entries (1 provisional, 1 approved)", fresh.load().line());
    }

    @Test
    void approveToolMarksTheEntryAndWritesTheFileBack() throws Exception {
        LearnedEntry saved = saveOne();
        Map<String, String> env = Map.of("LEARNED_APPROVE", saved.entryId(), "LEARNED_APPROVED_BY", "CA Reviewer");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        LearnedLibraryTool.run(new LearnedLibrary(props, om), env::get, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("learned library: 1 entries (1 provisional, 0 approved)"), out);
        assertTrue(out.contains("approved " + saved.entryId() + " by CA Reviewer"), out);
        assertTrue(out.contains(saved.entryId() + " | approved by CA Reviewer | notice Notice_07 Partial issue #3"), out);

        LearnedLibrary fresh = new LearnedLibrary(props, om);
        assertEquals("learned library: 1 entries (0 provisional, 1 approved)", fresh.load().line());
        LearnedEntry back = fresh.entries().get(0);
        assertEquals("approved", back.status());
        assertEquals("CA Reviewer", back.approvedBy());
        assertNotNull(back.approvedAt());

        assertThrows(IllegalArgumentException.class, () -> LearnedLibraryTool.run(new LearnedLibrary(props, om),
                Map.of("LEARNED_APPROVE", saved.entryId())::get, new PrintStream(new ByteArrayOutputStream())));
        assertThrows(NoSuchElementException.class, () -> fresh.approve("LRN-00000000", "x"));
    }

    @Test
    void onlyNewIssueWriterOutputForAnUnmatchedIssueQualifies() {
        MatchedIssue none = noneIssue(1);
        MatchedIssue partial = new MatchedIssue(2, "partial", List.of("CARD-B"), "w", List.of(), Map.of("amount", 1.0), List.of(), List.of());
        MatchedIssue noneWithCard = new MatchedIssue(3, "none", List.of("CARD-C"), "w", List.of(), Map.of(), List.of(), List.of());
        assertTrue(LearnedLibrary.qualifies(none, "new_issue", false));
        assertTrue(LearnedLibrary.qualifies(none, "high_stakes", false));
        assertFalse(LearnedLibrary.qualifies(none, "new_issue", true), "placeholder");
        assertFalse(LearnedLibrary.qualifies(partial, "partial", false));
        assertFalse(LearnedLibrary.qualifies(partial, "high_stakes", false), "partial issue on the high-stakes writer");
        assertFalse(LearnedLibrary.qualifies(noneWithCard, "new_issue", false));
    }

    @Test
    void noticeIdForFileNames() {
        assertEquals("rfd-08-show-cause-final", LearnedLibrary.noticeIdForFile("RFD-08 Show Cause (Final)"));
        assertEquals("notice", LearnedLibrary.noticeIdForFile("  "));
    }
}
