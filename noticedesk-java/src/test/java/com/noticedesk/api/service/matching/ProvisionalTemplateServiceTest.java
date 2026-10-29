package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.matching.ProvisionalTemplateService.*;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Promotion of an approved AI issue section into a provisional card + template, with an in-memory store
 * (no database). The notice values below are synthetic.
 */
class ProvisionalTemplateServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID NOTICE = UUID.randomUUID();
    private static final UUID SECTION = UUID.randomUUID();
    private static final String LEGAL_NAME = "M/s Zenith Alloys Pvt. Ltd.";
    private static final String GSTIN = "27LLLLM4321P1Z2";

    /** In-memory {@link Store}. */
    static final class MemoryStore implements Store {
        final Map<UUID, AiSection> sections = new HashMap<>();
        final Map<UUID, IssueMatch> matches = new HashMap<>();
        final Map<UUID, NoticeFacts> notices = new HashMap<>();
        final List<IssueCard> cards = new ArrayList<>();
        final List<ReplyTemplate> templates = new ArrayList<>();
        final List<Provenance> provenance = new ArrayList<>();

        @Override public AiSection loadSection(UUID id, UUID tenantId) {
            AiSection s = sections.get(id);
            return s != null && (tenantId == null || tenantId.equals(s.tenantId())) ? s : null;
        }
        @Override public IssueMatch loadIssueMatch(UUID noticeId, int issueNo) { return matches.get(noticeId); }
        @Override public NoticeFacts loadNotice(UUID noticeId) { return notices.get(noticeId); }
        @Override public int highestAiCardNumber() {
            return cards.stream().filter(c -> c.cardId().startsWith("NDK-AI-")).mapToInt(c -> Integer.parseInt(c.cardId().substring(7))).max().orElse(0);
        }
        @Override public int highestCardVersion(String cardId) {
            return cards.stream().filter(c -> c.cardId().equals(cardId)).mapToInt(IssueCard::version).max().orElse(0);
        }
        @Override public int highestTemplateVersion(String templateId) {
            return templates.stream().filter(t -> t.templateId().equals(templateId)).mapToInt(ReplyTemplate::version).max().orElse(0);
        }
        @Override public void insertCard(IssueCard card, Provenance p) { cards.add(card); provenance.add(p); }
        @Override public void insertTemplate(ReplyTemplate t, Provenance p) { templates.add(t); }
        @Override public void markPromoted(UUID id, Promotion promotion) {
            AiSection s = sections.get(id);
            sections.put(id, new AiSection(s.id(), s.tenantId(), s.noticeId(), s.draftId(), s.issueNo(), s.model(), s.promptVersion(),
                    s.output(), ProvisionalTemplateService.SECTION_STATUS_PROMOTED, s.createdAt(), promotion));
        }
    }

    private MemoryStore store;
    private ProvisionalTemplateService service;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        service = new ProvisionalTemplateService(store);
        store.sections.put(SECTION, section());
        store.matches.put(NOTICE, new IssueMatch("none", List.of(), Map.of(
                "period", "FY 2022-23",
                "amount", 482310,
                "interest", 61200,
                "section", "Section 16(4)",
                "supplier_name", "Orion Traders"),
                "ITC claimed after the Section 16(4) date; no catalogue card covers the Section 16(5) relaxation"));
        store.notices.put(NOTICE, new NoticeFacts("ZD2710230012345", "DIN-20231015ABCDEF", LocalDate.of(2023, 10, 15), "2022-23",
                "73", "scn_73", LEGAL_NAME, "Zenith Alloys", GSTIN, new BigDecimal("543510")));
        store.cards.add(new IssueCard("CARD-004", 1, "Section 16(4) ITC time-bar", "s", List.of(), null, null, List.of(), List.of(),
                List.of(), "TPL-004", "active", null, null, null));
    }

    private static AiSection section() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("writer", "new_issue");
        output.put("sections", List.of(
                Map.of("section", 4, "after_block", "", "html",
                        "<p>The notice ZD2710230012345 dated 15.10.2023 proposes to disallow ITC of Rs. 4,82,310/- claimed by "
                        + "M/s Zenith Alloys Pvt. Ltd. (GSTIN 27LLLLM4321P1Z2) for FY 2022-23 under Section 16(4).</p>"
                        + "<p>Zenith Alloys Private Limited submits that the credit of Rs 4.82 lakh relates to invoices of Orion Traders "
                        + "received in April 2022 to March 2023, and interest of Rs 61,200 is not payable.</p>"),
                Map.of("section", 6, "after_block", "", "html",
                        "<p>Section 16(5) inserted by the Finance (No. 2) Act, 2024 extends the date for 2022-23. In Hindustan Steel "
                        + "Ltd. v. State of Orissa (1969) the Supreme Court held that penalty is not automatic.</p>"
                        + "<ul><li>The total demand of Rs 5,43,510 is therefore unsustainable against the noticee.</li></ul>")));
        output.put("citations", List.of(Map.of("case", "Hindustan Steel Ltd. v. State of Orissa", "court", "Supreme Court",
                "year", 1969, "check_status", "VERIFIED")));
        output.put("documents", List.of("GSTR-3B for FY 2022-23 of M/s Zenith Alloys Pvt. Ltd.", "Invoices of Orion Traders"));
        output.put("summary_line", "ITC of Rs 4,82,310 for FY 2022-23 claimed by Zenith Alloys is protected by Section 16(5).");
        output.put("strength_note", "Strong: statutory relaxation.");
        return new AiSection(SECTION, TENANT, NOTICE, UUID.randomUUID(), 2, "claude-opus-5-5", "new_issue_v2", output,
                "pending_review", OffsetDateTime.now(), null);
    }

    @Test
    void promotion_replacesEveryNoticeValueWithPlaceholders() {
        PromotionResult r = service.promote(SECTION, "CA Reviewer", null);

        assertTrue(r.created());
        assertEquals("NDK-AI-001", r.cardId());
        assertEquals(1, r.cardVersion());
        assertEquals("TPL-NDK-AI-001", r.templateId());
        assertEquals(1, r.templateVersion());

        ReplyTemplate t = store.templates.get(0);
        assertEquals("provisional", t.status());
        assertEquals("NDK-AI-001", t.cardId());
        assertEquals("CA Reviewer", t.approvedBy());
        assertNotNull(t.approvedAt());
        assertEquals(LocalDate.of(2022, 4, 1), t.effectiveFrom());
        assertEquals(4, t.blocks().size(), t.blocks().toString());
        String all = String.join("\n", t.blocks().stream().map(TemplateBlock::html).toList()) + "\n" + t.summaryLine()
                + "\n" + String.join("\n", t.documents());

        // no real value survives, in any of the forms the writer used
        for (String leaked : List.of("Zenith", "27LLLLM4321P1Z2", "ZD2710230012345", "4,82,310", "482310", "4.82 lakh", "61,200",
                "5,43,510", "15.10.2023", "Orion Traders", "FY 2022-23", "April 2022")) {
            assertFalse(all.contains(leaked), "value still present: " + leaked + "\n" + all);
        }
        TemplateBlock first = t.blocks().get(0);
        assertEquals(4, first.section());
        assertEquals("BLK-NDK-AI-001-04-01", first.id());
        assertTrue(first.html().contains("{{notice.reference}} dated {{notice.issue_date}}"), first.html());
        assertTrue(first.html().contains("ITC of {{issue.amount_tax}} claimed by {{client.legal_name}} (GSTIN {{client.gstin}}) for {{issue.period}}"), first.html());
        assertTrue(first.html().contains("under Section 16(4)"), "legal content is kept");
        TemplateBlock second = t.blocks().get(1);
        assertTrue(second.html().contains("{{client.legal_name}} submits that the credit of {{issue.amount_tax}} relates to invoices of {{issue.supplier_name}}"), second.html());
        assertTrue(second.html().contains("received in {{issue.period}}, and interest of {{issue.interest}}"), second.html());
        TemplateBlock third = t.blocks().get(2);
        assertEquals(6, third.section());
        assertTrue(third.html().contains("extends the date for {{issue.period}}"), third.html());
        assertTrue(third.html().contains("Hindustan Steel"), "citations stay in the text");
        assertEquals(List.of("Hindustan Steel Ltd. v. State of Orissa (Supreme Court, 1969) [VERIFIED]"), third.citations());
        assertEquals(List.of(), first.citations());
        TemplateBlock fourth = t.blocks().get(3);
        assertTrue(fourth.html().startsWith("<ul>"), fourth.html());
        assertTrue(fourth.html().contains("{{notice.total_demand_amount}}"), fourth.html());

        assertEquals(List.of("GSTR-3B for {{issue.period}} of {{client.legal_name}}", "Invoices of {{issue.supplier_name}}"), t.documents());
        assertTrue(t.summaryLine().contains("ITC of {{issue.amount_tax}} for {{issue.period}} claimed by {{client.legal_name}}"), t.summaryLine());
        assertEquals(Set.of("client.legal_name", "client.gstin", "notice.reference", "notice.issue_date", "issue.amount_tax",
                "issue.period", "issue.interest", "issue.supplier_name", "notice.total_demand_amount"), new HashSet<>(t.variables()));

        IssueCard card = store.cards.stream().filter(c -> c.cardId().equals("NDK-AI-001")).findFirst().orElseThrow();
        assertEquals("draft", card.status());
        assertEquals("TPL-NDK-AI-001", card.templateId());
        assertEquals(SECTION.toString(), card.sourceAiSectionId());
        assertTrue(card.isProvisional());
        assertEquals(List.of("Section 16(4)"), card.sections());
        assertEquals(List.of("scn_73"), card.stages());
        assertEquals("2022-04-01", card.periodFrom());
        assertEquals("2023-03-31", card.periodTo());
        assertFalse(card.title().contains("Zenith"), card.title());
        assertFalse(card.summary().contains("Zenith"), card.summary());
        assertTrue(card.summary().startsWith("[PROVISIONAL"), card.summary());
        assertTrue(card.keywords().contains("section 16(4)"), card.keywords().toString());

        Provenance p = store.provenance.get(0);
        assertEquals(SECTION, p.aiSectionId());
        assertEquals(NOTICE, p.noticeId());
        assertEquals(TENANT, p.tenantId());
        assertEquals("promoted", store.sections.get(SECTION).status());
        assertEquals("TPL-NDK-AI-001", store.sections.get(SECTION).promotion().templateId());
    }

    @Test
    void secondPromotion_isANoOp_returningTheExistingTemplate() {
        PromotionResult first = service.promote(SECTION, "CA Reviewer", null);
        PromotionResult again = service.promote(SECTION, "Someone Else", null);

        assertFalse(again.created());
        assertEquals(first.templateId(), again.templateId());
        assertEquals(first.templateVersion(), again.templateVersion());
        assertEquals(first.cardId(), again.cardId());
        assertEquals(1, store.templates.size());
        assertEquals(2, store.cards.size(), "the seeded card plus one promoted card");
    }

    @Test
    void promotion_ontoAnExistingCard_createsANewVersionOfThatCard() {
        PromotionResult r = service.promote(SECTION, "CA Reviewer", "CARD-004");

        assertEquals("CARD-004", r.cardId());
        assertEquals(2, r.cardVersion());
        assertEquals("TPL-CARD-004", r.templateId());
        IssueCard v2 = store.cards.stream().filter(c -> c.cardId().equals("CARD-004") && c.version() == 2).findFirst().orElseThrow();
        assertEquals("draft", v2.status());
        assertTrue(v2.isProvisional());
        assertEquals("TPL-CARD-004", v2.templateId());
        store.sections.put(SECTION, section());   // not yet promoted again
        assertThrows(IllegalArgumentException.class, () -> service.promote(SECTION, "x", "CARD-999"), "unknown card is refused");
        assertThrows(NoSuchElementException.class, () -> service.promote(UUID.randomUUID(), "x", null), "unknown section");
    }

    @Test
    void tenantMismatch_readsAsNotFound_andBlankApproverIsRefused() {
        assertThrows(NoSuchElementException.class, () -> service.promote(SECTION, "CA Reviewer", null, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> service.promote(SECTION, " ", null));
        assertTrue(store.templates.isEmpty());
    }

    @Test
    void aValueThatCannotBeReplaced_refusesThePromotion() {
        // the GSTIN glued to a word is not replaced by the rule (no boundary) but still trips the leak check
        Map<String, Object> output = new LinkedHashMap<>(section().output());
        output.put("sections", List.of(Map.of("section", 4, "after_block", "", "html", "<p>GSTIN27LLLLM4321P1Z2 claims the credit.</p>")));
        store.sections.put(SECTION, new AiSection(SECTION, TENANT, NOTICE, null, 2, "m", "v", output, "pending_review", OffsetDateTime.now(), null));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.promote(SECTION, "CA Reviewer", null));
        assertTrue(e.getMessage().contains("GSTIN"), e.getMessage());
        assertTrue(store.templates.isEmpty(), "nothing written");
        assertNull(store.sections.get(SECTION).promotion());
    }

    @Test
    void provisionalTemplate_isPickedByTheFillService_withTheInfoFlag() {
        service.promote(SECTION, "CA Reviewer", null);
        ReplyTemplate promoted = store.templates.get(0);

        TemplateFillService fill = new TemplateFillService(null, new AppProperties(), new ObjectMapper());   // allow-draft-cards false
        fill.registerInMemoryTemplate(promoted);
        MatchedIssue issue = new MatchedIssue(1, "full", List.of("NDK-AI-001"), "why", List.of(),
                Map.of("period", "FY 2023-24", "amount", 125000, "interest", 9000, "supplier_name", "Nova Metals"), List.of(), List.of());
        MatchedNoticeInfo notice = new MatchedNoticeInfo("ZD9900000000001", "DIN-X", "2024-11-02", null, "2023-24", "FY 2023-24", 125000.0, "STO");
        DraftingInput input = new DraftingInput(UUID.randomUUID(), "formal", null, "M/s Beta Castings Pvt Ltd", "AAACB1234A", "Company",
                "Regular", "27AAACB1234A1Z1", "Maharashtra", "CGST Act", "2023-24", "2024-25",
                Map.of("issue_date", "2024-11-02", "total_demand_amount", 140000), Map.of(), "OCR",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);

        FilledTemplateResult result = fill.fillTemplateForIssue(issue, notice, input);

        assertEquals("TPL-NDK-AI-001", result.templateId());
        assertTrue(result.flags().contains(TemplateFillService.FLAG_PROVISIONAL_TEMPLATE), result.flags().toString());
        assertTrue(result.missingVars().isEmpty(), result.missingVars().toString());
        String html = result.blocks().get(0).filledHtml();
        assertTrue(html.contains("The notice ZD9900000000001 dated 02-11-2024"), html);
        assertTrue(html.contains("ITC of Rs 1,25,000 claimed by M/s Beta Castings Pvt Ltd (GSTIN 27AAACB1234A1Z1) for FY 2023-24"), html);
        assertFalse(html.contains("Zenith"));
        assertTrue(result.blocks().get(1).filledHtml().contains("invoices of Nova Metals"), result.blocks().get(1).filledHtml());
        assertTrue(result.blocks().get(1).filledHtml().contains("interest of Rs 9,000"), result.blocks().get(1).filledHtml());

        // the flag becomes the info check FLAG_PROVISIONAL_TEMPLATE in the partner note
        DraftCheckService checks = new DraftCheckService(null);
        assertTrue(checks.flagChecks(result.flags()).stream()
                .anyMatch(c -> c.checkName().equals("FLAG_PROVISIONAL_TEMPLATE") && "info".equals(c.severity())));
    }

    @Test
    void catalogueInclusionToggle() {
        IssueCard active = card("CARD-001", "active", null);
        IssueCard devDraft = card("CARD-002", "draft", null);
        IssueCard provisional = card("NDK-AI-001", "draft", SECTION.toString());
        IssueCard retired = card("CARD-003", "retired", null);
        List<IssueCard> all = List.of(active, devDraft, provisional, retired);

        assertEquals(List.of(active, provisional), CatalogueService.visibleCards(all, false, true), "default: provisional included");
        assertEquals(List.of(active), CatalogueService.visibleCards(all, false, false), "include-provisional=false");
        assertEquals(List.of(active, devDraft, provisional), CatalogueService.visibleCards(all, true, false), "allow-draft-cards shows every draft");

        AppProperties props = new AppProperties();
        CatalogueService catalogue = new CatalogueService(null, props, new ObjectMapper());
        assertTrue(catalogue.includeProvisional(), "default true");
        props.getCatalogue().setIncludeProvisional(false);
        assertFalse(catalogue.includeProvisional());
        assertEquals(List.of(), catalogue.getActiveCards(), "no database: nothing");
    }

    private static IssueCard card(String id, String status, String source) {
        return new IssueCard(id, 1, "t", "s", List.of(), null, null, List.of(), List.of(), List.of(), null, status, null, null, null, source);
    }

    @Test
    void scrubber_handlesTheUsualForms() {
        TemplateScrubber s = new TemplateScrubber(new TemplateScrubber.Values()
                .legalName("M/s Alpha Beta Industries Private Limited").gstin("27BBBCC9876K1Z9")
                .reference("ZD0707240001234").issueDate(LocalDate.of(2024, 7, 8))
                .amountTax(new BigDecimal("1500000")).period("April 2021 to March 2022"));
        assertEquals("{{client.legal_name}} / {{client.legal_name}} / {{client.legal_name}}",
                s.scrub("M/s. Alpha Beta Industries Pvt. Ltd. / Alpha Beta Industries / ALPHA BETA INDUSTRIES PRIVATE LIMITED"));
        assertEquals("{{issue.amount_tax}}, {{issue.amount_tax}}, {{issue.amount_tax}}, {{issue.amount_tax}}, {{issue.amount_tax}}",
                s.scrub("Rs. 15,00,000/-, INR 1,500,000.00, 1500000, Rs 15 lakh, 15.00 lakhs"));
        assertEquals("{{notice.issue_date}} {{notice.issue_date}} {{notice.issue_date}} {{notice.issue_date}} {{notice.issue_date}}",
                s.scrub("08-07-2024 8.7.2024 2024-07-08 8th July, 2024 July 8, 2024"));
        assertEquals("{{issue.period}}; {{issue.period}}; {{issue.period}}; {{issue.period}}; {{issue.period}}",
                s.scrub("April 2021 to March 2022; FY 2021-22; F.Y. 2021-2022; 2021-22; 01.04.2021 to 31.03.2022"));
        assertEquals("ref {{notice.reference}} gstin {{client.gstin}}", s.scrub("ref ZD0707240001234 gstin 27bbbcc9876k1z9"));
        assertEquals("Section 16(4) and 2,50,000 stay", s.scrub("Section 16(4) and 2,50,000 stay"), "other numbers and law untouched");
        assertEquals("{{client.legal_name}} could", s.scrub("AlphaBetaIndustries could"), "joined name is caught; 'co' never eats 'could'");
        assertThrows(IllegalStateException.class, () -> s.scrub("no27BBBCC9876K1Z9"), "GSTIN glued to a word is a leak");
    }
}
