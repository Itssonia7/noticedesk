package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.util.HtmlSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Turns an approved AI-written issue section ({@code ai_issue_sections}) into provisional catalogue
 * content: a new 'draft' issue card (NDK-AI-nnn, or a new version of an existing card) and a
 * 'provisional' reply template whose blocks are the section's 04/05/06 paragraphs with every
 * notice-specific value replaced by a placeholder ({@link TemplateScrubber}). Citations are carried over
 * with their IndianKanoon check status, the documents from Section 10. Provenance (source section,
 * notice, approver, time) is written on the card, the template and the section; promoting the same
 * section again returns the existing template.
 *
 * <p>Storage is behind {@link Store} so the conversion is unit-testable without a database
 * ({@link JdbcPromotionStore} is the production implementation).
 */
@Service
@Slf4j
public class ProvisionalTemplateService {

    public static final String AI_CARD_PREFIX = "NDK-AI-";
    public static final String SECTION_STATUS_PROMOTED = "promoted";

    /** One {@code ai_issue_sections} row. {@code promotion} is non-null once the section was promoted. */
    public record AiSection(UUID id, UUID tenantId, UUID noticeId, UUID draftId, int issueNo, String model,
                            String promptVersion, Map<String, Object> output, String status, OffsetDateTime createdAt,
                            Promotion promotion) {}

    /** What a section was promoted into. */
    public record Promotion(String cardId, int cardVersion, String templateId, int templateVersion,
                            String promotedBy, OffsetDateTime promotedAt) {}

    /** The matcher's facts for the issue (latest {@code issue_matches} row), or empty when none was stored. */
    public record IssueMatch(String status, List<String> cardIds, Map<String, Object> facts, String why) {
        public static IssueMatch empty() {
            return new IssueMatch(null, List.of(), Map.of(), null);
        }
    }

    /** The notice's own fields (the values that must not survive in a template). */
    public record NoticeFacts(String noticeNumber, String din, LocalDate issueDate, String financialYear,
                              String section, String documentType, String clientLegalName, String clientTradeName,
                              String gstin, BigDecimal totalDemand) {}

    public record Provenance(UUID aiSectionId, UUID noticeId, UUID tenantId, String approvedBy, OffsetDateTime approvedAt) {}

    /** Result of {@link #promote}; {@code created} is false when the section had already been promoted. */
    public record PromotionResult(String cardId, int cardVersion, String templateId, int templateVersion,
                                  boolean created, List<String> variables, int blocks, int citations) {}

    /** Storage used by the service (JDBC in production, in-memory in tests). */
    public interface Store {
        /** The section, or null when it does not exist (or belongs to another tenant when {@code tenantId} is given). */
        AiSection loadSection(UUID aiSectionId, UUID tenantId);
        IssueMatch loadIssueMatch(UUID noticeId, int issueNo);
        NoticeFacts loadNotice(UUID noticeId);
        /** Highest n of the NDK-AI-nnn card ids so far (0 when none). */
        int highestAiCardNumber();
        /** Highest version of {@code cardId} (0 when the card does not exist). */
        int highestCardVersion(String cardId);
        /** Highest version of {@code templateId} (0 when none). */
        int highestTemplateVersion(String templateId);
        void insertCard(IssueCard card, Provenance provenance);
        void insertTemplate(ReplyTemplate template, Provenance provenance);
        void markPromoted(UUID aiSectionId, Promotion promotion);
    }

    private final Store store;

    public ProvisionalTemplateService(Store store) {
        this.store = store;
    }

    /** As {@link #promote(UUID, String, String, UUID)} without a tenant check (service-level callers). */
    public PromotionResult promote(UUID aiSectionId, String approvedBy, String cardId) {
        return promote(aiSectionId, approvedBy, cardId, null);
    }

    /**
     * Promotes the section. Idempotent: a section already promoted returns its existing card / template with
     * {@code created=false}.
     *
     * @param cardId   when given, the template is attached to a NEW VERSION of this existing card instead of
     *                 a new NDK-AI card
     * @param tenantId when given, a section of another tenant reads as not found
     * @throws NoSuchElementException when the section does not exist
     * @throws IllegalStateException  when a notice-specific value could not be replaced (nothing is written)
     */
    @Transactional
    public PromotionResult promote(UUID aiSectionId, String approvedBy, String cardId, UUID tenantId) {
        if (aiSectionId == null) throw new IllegalArgumentException("aiSectionId is required");
        if (approvedBy == null || approvedBy.isBlank()) throw new IllegalArgumentException("approvedBy is required");
        AiSection section = store.loadSection(aiSectionId, tenantId);
        if (section == null) throw new NoSuchElementException("AI section not found: " + aiSectionId);
        if (section.promotion() != null) {
            Promotion p = section.promotion();
            log.info("AI section {} already promoted to {} v{} / {} v{}; returning it", aiSectionId,
                    p.cardId(), p.cardVersion(), p.templateId(), p.templateVersion());
            return new PromotionResult(p.cardId(), p.cardVersion(), p.templateId(), p.templateVersion(), false, List.of(), 0, 0);
        }

        IssueMatch match = Optional.ofNullable(store.loadIssueMatch(section.noticeId(), section.issueNo())).orElse(IssueMatch.empty());
        NoticeFacts notice = store.loadNotice(section.noticeId());
        OffsetDateTime now = OffsetDateTime.now();
        Provenance provenance = new Provenance(section.id(), section.noticeId(), section.tenantId(), approvedBy.trim(), now);

        String targetCard;
        int cardVersion;
        if (cardId != null && !cardId.isBlank()) {
            targetCard = cardId.trim();
            int existing = store.highestCardVersion(targetCard);
            if (existing == 0) throw new IllegalArgumentException("cardId " + cardId + " does not exist");
            cardVersion = existing + 1;
        } else {
            targetCard = String.format("%s%03d", AI_CARD_PREFIX, store.highestAiCardNumber() + 1);
            cardVersion = 1;
        }
        String templateId = "TPL-" + targetCard;
        int templateVersion = store.highestTemplateVersion(templateId) + 1;

        Built built = build(section, match, notice, targetCard, cardVersion, templateId, templateVersion, provenance);

        store.insertCard(built.card(), provenance);
        store.insertTemplate(built.template(), provenance);
        Promotion promotion = new Promotion(targetCard, cardVersion, templateId, templateVersion, provenance.approvedBy(), now);
        store.markPromoted(section.id(), promotion);
        log.info("AI section {} (notice {}, issue #{}) promoted by {} to card {} v{} and provisional template {} v{} ({} blocks, variables {})",
                section.id(), section.noticeId(), section.issueNo(), approvedBy, targetCard, cardVersion, templateId, templateVersion,
                built.template().blocks().size(), built.template().variables());
        return new PromotionResult(targetCard, cardVersion, templateId, templateVersion, true,
                built.template().variables(), built.template().blocks().size(), built.citationCount());
    }

    public record Built(IssueCard card, ReplyTemplate template, int citationCount) {}

    private static final Pattern BLOCK_SPLIT = Pattern.compile("(?i)(?<=</p>)|(?<=</ul>)|(?<=</ol>)|(?<=</table>)|(?<=</h[1-6]>)|(?<=</blockquote>)");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Set<String> KEYWORD_STOP = Set.of("the", "and", "of", "for", "under", "with", "from", "that", "this",
            "issue", "notice", "section", "act", "cgst", "sgst", "igst", "gst", "tax", "demand", "proposed", "alleged", "on",
            "in", "to", "by", "as", "is", "are", "was", "were", "has", "have", "been", "not", "any", "all", "per", "rs");

    /**
     * Pure conversion of the section into a card and a template (nothing is written). Public for tests.
     *
     * @throws IllegalStateException when a notice-specific value could not be replaced
     */
    public static Built build(AiSection section, IssueMatch match, NoticeFacts notice, String cardId, int cardVersion,
                              String templateId, int templateVersion, Provenance provenance) {
        Map<String, Object> facts = match != null && match.facts() != null ? match.facts() : Map.of();
        TemplateScrubber scrubber = new TemplateScrubber(values(facts, notice));
        Map<String, Object> output = section.output() != null ? section.output() : Map.of();

        // ---- blocks: every paragraph of the 04/05/06 fragments, values replaced
        List<TemplateBlock> blocks = new ArrayList<>();
        List<Map<String, Object>> citations = listOfMaps(output.get("citations"));
        int[] counter = new int[7];
        for (Map<String, Object> frag : listOfMaps(output.get("sections"))) {
            int sec = toInt(frag.get("section"), 0);
            if (sec != 4 && sec != 5 && sec != 6) continue;
            String html = Objects.toString(frag.get("html"), "");
            for (String para : splitParagraphs(html)) {
                String scrubbed = HtmlSanitizer.sanitize(scrubber.scrub(para));
                if (scrubbed.isBlank()) continue;
                counter[sec]++;
                String id = String.format("BLK-%s-%02d-%02d", cardId, sec, counter[sec]);
                blocks.add(new TemplateBlock(id, sec, scrubbed, citationsIn(para, citations), null));
            }
        }
        if (blocks.isEmpty()) {
            throw new IllegalStateException("AI section " + section.id() + " has no section 04/05/06 text to promote");
        }

        // ---- documents (Section 10) and summary line
        List<String> documents = new ArrayList<>();
        for (Object d : listOf(output.get("documents"))) {
            String doc = scrubber.scrub(Objects.toString(d, "")).trim();
            if (!doc.isEmpty() && !documents.contains(doc)) documents.add(doc);
        }
        String summaryLine = output.get("summary_line") != null ? scrubber.scrub(output.get("summary_line").toString()).trim() : null;

        // ---- card content from the issue facts
        // the card is catalogue text (no placeholders): facts title, else the matcher's reason, else the summary line
        String why = match != null && match.why() != null && !match.why().isBlank() ? humanise(scrubber.scrub(match.why())) : null;
        String title = firstText(facts, "issue_title", "title", "issue", "ground", "heading");
        if (title != null) title = humanise(scrubber.scrub(title));
        if (title == null && why != null) title = plain(why);
        if (title == null && summaryLine != null) title = plain(humanise(summaryLine));
        if (title == null) title = "AI-drafted issue #" + section.issueNo() + " (" + section.promptVersion() + ")";
        title = truncate(title, 160);
        String summary = "[PROVISIONAL - promoted from an AI section, not CA reviewed] "
                + (summaryLine != null && !summaryLine.isBlank() ? plain(humanise(summaryLine)) : title);
        if (why != null && !summary.contains(why)) summary = summary + " Matcher note: " + plain(why);
        summary = truncate(summary, 1000);
        List<String> sections = sectionsOf(facts);
        List<String> keywords = keywordsOf(title, sections, facts);
        String stage = StageDetector.detect(notice != null ? notice.section() : null, notice != null ? notice.documentType() : null);
        List<String> stages = StageDetector.UNKNOWN.equals(stage) ? List.of() : List.of(stage);
        String periodText = firstText(facts, "period", "tax_period");
        Optional<PeriodNormalizer.Period> period = PeriodNormalizer.parse(periodText);
        String periodFrom = period.map(p -> p.start().toString()).orElse(null);
        String periodTo = period.map(p -> p.end().toString()).orElse(null);
        List<String> notThisIf = List.of("The AI section this card was promoted from was written for one notice; confirm the facts match before reuse");

        IssueCard card = new IssueCard(cardId, cardVersion, title, summary, sections, periodFrom, periodTo, stages, keywords,
                notThisIf, templateId, "draft", null, null, provenance.approvedAt(), provenance.aiSectionId().toString());

        // every placeholder the scrubber inserted must be declared, else the fill reports a template bug
        List<String> variables = new ArrayList<>(scrubber.placeholdersUsed());
        ReplyTemplate template = new ReplyTemplate(templateId, templateVersion, cardId,
                period.map(PeriodNormalizer.Period::start).orElse(null), null,
                TemplateFillService.STATUS_PROVISIONAL, blocks, variables, documents,
                summaryLine != null && !summaryLine.isBlank() ? summaryLine : null,
                List.of(), provenance.approvedBy(), provenance.approvedAt(), provenance.approvedAt());
        return new Built(card, template, citations.size());
    }

    // ---------------------------------------------------------------- helpers

    private static TemplateScrubber.Values values(Map<String, Object> facts, NoticeFacts notice) {
        TemplateScrubber.Values v = new TemplateScrubber.Values();
        if (notice != null) {
            v.legalName(notice.clientLegalName()).tradeName(notice.clientTradeName()).gstin(notice.gstin())
             .reference(notice.noticeNumber()).reference(notice.din()).issueDate(notice.issueDate()).totalDemand(notice.totalDemand());
        }
        String period = firstText(facts, "period", "tax_period");
        if (period == null && notice != null) period = notice.financialYear();
        v.period(period);
        Object amount = facts.get("amount") != null ? facts.get("amount") : facts.get("amount_tax");
        BigDecimal amountTax = toAmount(amount);
        if (amountTax != null) v.amountTax(amountTax);
        for (Map.Entry<String, Object> e : facts.entrySet()) {
            String key = e.getKey();
            if (key == null || Set.of("amount", "amount_tax", "period", "tax_period").contains(key)) continue;
            v.fact(key, e.getValue());
        }
        return v;
    }

    private static BigDecimal toAmount(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = v.toString().replaceAll("[^0-9.]", "");
        if (s.isEmpty() || s.equals(".")) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static List<String> splitParagraphs(String html) {
        List<String> out = new ArrayList<>();
        if (html == null) return out;
        for (String part : BLOCK_SPLIT.split(html)) {
            String p = part.trim();
            if (!p.isEmpty() && !TAGS.matcher(p).replaceAll("").isBlank()) out.add(p);
        }
        if (out.isEmpty() && !html.isBlank()) out.add(html.trim());
        return out;
    }

    /** "Case name (court, year) [CHECK_STATUS]" for every citation whose case name appears in the paragraph. */
    private static List<String> citationsIn(String paragraphHtml, List<Map<String, Object>> citations) {
        List<String> out = new ArrayList<>();
        // every distinctive word of the case name (party names without reporter, punctuation or company suffixes)
        // must occur in the paragraph
        Set<String> words = new HashSet<>(Arrays.asList(TAGS.matcher(paragraphHtml).replaceAll(" ").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim().split("\\s+")));
        for (Map<String, Object> c : citations) {
            String name = Objects.toString(c.get("case"), "").trim();
            if (name.isEmpty()) continue;
            String identity = CitationVerificationAgent.citationIdentity(name);
            if (identity.isEmpty()) continue;
            boolean present = true;
            for (String w : identity.split(" ")) {
                if (!w.isEmpty() && !w.equals("v") && !words.contains(w)) { present = false; break; }
            }
            if (!present) continue;
            String label = name
                    + (c.get("court") != null || c.get("year") != null
                        ? " (" + Objects.toString(c.get("court"), "court not given") + ", " + Objects.toString(c.get("year"), "year not given") + ")"
                        : "")
                    + " [" + Objects.toString(c.get("check_status"), "NOT_CHECKED") + "]";
            if (!out.contains(label)) out.add(label);
        }
        return out;
    }

    private static List<String> sectionsOf(Map<String, Object> facts) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String key : List.of("section", "sections", "provision", "provisions")) {
            Object v = facts.get(key);
            if (v instanceof Collection<?> c) {
                for (Object o : c) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            } else if (v != null && !v.toString().isBlank()) {
                for (String s : v.toString().split("[;,]")) if (!s.isBlank()) out.add(s.trim());
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> keywordsOf(String title, List<String> sections, Map<String, Object> facts) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : sections) out.add(s.toLowerCase(Locale.ROOT));
        for (String t : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9()./ -]", " ").split("\\s+")) {
            if (t.length() >= 4 && !KEYWORD_STOP.contains(t) && !t.matches("\\d+")) out.add(t);
            if (out.size() >= 12) break;
        }
        Object kw = facts.get("keywords");
        if (kw instanceof Collection<?> c) for (Object o : c) if (o != null) out.add(o.toString().toLowerCase(Locale.ROOT));
        return new ArrayList<>(out);
    }

    private static String firstText(Map<String, Object> facts, String... keys) {
        for (String k : keys) {
            Object v = facts.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString().trim();
        }
        return null;
    }

    /** Placeholders of a scrubbed text as readable words, for card text that is never filled. */
    static String humanise(String scrubbed) {
        if (scrubbed == null) return null;
        return scrubbed
                .replace("{{" + TemplateScrubber.CLIENT_LEGAL_NAME + "}}", "the taxpayer")
                .replace("{{" + TemplateScrubber.CLIENT_TRADE_NAME + "}}", "the taxpayer")
                .replace("{{" + TemplateScrubber.CLIENT_GSTIN + "}}", "the GSTIN")
                .replace("{{" + TemplateScrubber.NOTICE_REFERENCE + "}}", "the notice")
                .replace("{{" + TemplateScrubber.NOTICE_ISSUE_DATE + "}}", "the notice date")
                .replace("{{" + TemplateScrubber.NOTICE_TOTAL_DEMAND + "}}", "the total demand")
                .replace("{{" + TemplateScrubber.ISSUE_AMOUNT_TAX + "}}", "the disputed amount")
                .replace("{{" + TemplateScrubber.ISSUE_PERIOD + "}}", "the tax period")
                .replaceAll("\\{\\{[^}]+\\}\\}", "the stated value")
                .replaceAll("\\s+", " ").trim();
    }

    private static String plain(String html) {
        return TAGS.matcher(html).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3).trim() + "...";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private static List<Object> listOf(Object v) {
        return v instanceof Collection<?> c ? new ArrayList<>(c) : List.of();
    }

    private static int toInt(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v != null ? Integer.parseInt(v.toString().trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
