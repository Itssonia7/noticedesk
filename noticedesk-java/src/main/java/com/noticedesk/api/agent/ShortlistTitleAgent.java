package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import com.noticedesk.api.service.matching.CatalogueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v7 Stage 1 shortlist, AI title pass: one small call per notice, before matching, that reads the notice by meaning
 * against the one-line titles of the whole visible catalogue and names every card that could plausibly apply. It
 * catches the cards label scoring misses (a defence card whose section the notice does not cite; a procedural card
 * whose keyword is absent precisely because the defect exists); {@code CardShortlister} unites its picks with the
 * label picks.
 *
 * <p>The system prompt is the instructions plus the catalogue as {@code NDK-001 | <title>} lines: the same for every
 * notice, so it is the cached prefix; the user message is the notice type and the notice text. Model
 * {@code LLM_MODEL_SHORTLIST} (blank: the matching model), agent key {@code shortlist} (effort
 * {@code LLM_EFFORT_SHORTLIST}, default low; max_tokens {@code matching.shortlist.title-max-tokens}, default 6000, and
 * {@code title-retry-max-tokens}, default 9000, after a max_tokens stop: thinking counts against it). The call goes
 * through {@link WriterCall} (budget pre-check, usage logging, one retry on unusable output, text blocks only,
 * {@link LlmFatalException} passed on); only the outcome of the attempt pair is reported to the fail-fast guard, so a
 * truncated first attempt that the retry repairs is not a failure. A failed or refused call returns null: the
 * shortlist then uses labels only.
 *
 * <p>The answer is ids only ({@code {"card_ids": [...]}}), ranked most likely first; only the first
 * {@code matching.shortlist.title-max-cards} (default 15, env SHORTLIST_TITLE_MAX_CARDS) are kept, in that order, and
 * the call is made at temperature 0 where the model takes one, so the same notice gives the same shortlist and the
 * same matching prompt size from run to run. The provider abstraction has no JSON output mode, so the parser is
 * tolerant: fences are stripped, and when the JSON cannot be read the catalogue ids found in the text
 * ({@link #CARD_ID}) are used, with {@link Picks#parseFallback()} set (flag {@code warn:shortlist_ai_parse_fallback}).
 */
@Service
@Slf4j
public class ShortlistTitleAgent {

    /** File {@code prompts/shortlist_titles_v1.md}; its header carries the revision (v1.1: ids-only answer; v1.2: ranked). */
    public static final String PROMPT_VERSION = "shortlist_titles_v1";
    /** Card ids looked for in an answer whose JSON cannot be read. */
    static final Pattern CARD_ID = Pattern.compile("\\bNDK-(?:AI-)?\\d{3}\\b");
    /** Agent key for effort / max_tokens / budget lines ({@code step=shortlist} in the call log). */
    public static final String AGENT_KEY = AppProperties.Llm.SHORTLIST;

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ApiUsageLogService apiUsageLogService;
    private final LlmFactory llmFactory;

    @Autowired
    public ShortlistTitleAgent(AppProperties properties, ObjectMapper objectMapper, ApiUsageLogService apiUsageLogService,
                               LlmFactory llmFactory) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.apiUsageLogService = apiUsageLogService;
        this.llmFactory = llmFactory;
    }

    /**
     * The cards kept from the model's ranked answer (catalogue ids only, in the model's order, at most
     * {@code matching.shortlist.title-max-cards}), the model that answered, whether the ids were found by
     * {@link #CARD_ID} in an answer whose JSON could not be read, and how many catalogue ids the answer named before
     * the cap ({@code returnedCount} >= {@code cardIds.size()}).
     */
    public record Picks(Set<String> cardIds, String model, boolean parseFallback, int returnedCount) {
        public Picks {
            cardIds = cardIds != null ? Collections.unmodifiableSet(new LinkedHashSet<>(cardIds)) : Set.of();
            returnedCount = Math.max(returnedCount, cardIds.size());
        }

        public Picks(Set<String> cardIds, String model, boolean parseFallback) {
            this(cardIds, model, parseFallback, 0);
        }

        public Picks(Set<String> cardIds, String model) {
            this(cardIds, model, false);
        }

        /** True when the answer named more cards than were kept. */
        public boolean capped() {
            return returnedCount > cardIds.size();
        }
    }

    /** Parsed answer: the ids (model order, capped), whether the regex fallback found them, and how many came back. */
    record Parsed(Set<String> ids, boolean fallback, int returnedCount) {
        Parsed(Set<String> ids, boolean fallback) {
            this(ids, fallback, ids != null ? ids.size() : 0);
        }
    }

    /** {@code matching.shortlist.title-max-cards} (env SHORTLIST_TITLE_MAX_CARDS); 0 = keep every id. */
    int maxCards() {
        if (properties.getMatching() == null || properties.getMatching().getShortlist() == null) {
            return AppProperties.Matching.Shortlist.DEFAULT_TITLE_MAX_CARDS;
        }
        return Math.max(0, properties.getMatching().getShortlist().getTitleMaxCards());
    }

    /** The first {@link #maxCards()} ids of a ranked answer, in the answer's order. */
    Parsed cap(Parsed parsed) {
        int max = maxCards();
        if (max <= 0 || parsed.ids().size() <= max) return parsed;
        Set<String> kept = new LinkedHashSet<>();
        for (String id : parsed.ids()) {
            if (kept.size() >= max) break;
            kept.add(id);
        }
        log.info("ShortlistTitleAgent: {} card(s) named, the first {} kept (matching.shortlist.title-max-cards)", parsed.ids().size(), max);
        return new Parsed(kept, parsed.fallback(), parsed.ids().size());
    }

    /** The model of this pass: {@code model-shortlist}, else the matching model; null when neither is set. */
    public String model() {
        String m = properties.getLlm() != null ? properties.getLlm().shortlistModel() : null;
        return m != null && !m.isBlank() ? m.trim() : null;
    }

    /** False in stub mode, without a factory or without a model: the caller then shortlists by labels only. */
    public boolean available() {
        if (llmFactory == null || properties.getLlm() == null) return false;
        if ("stub".equalsIgnoreCase(properties.getLlm().getProviderPrimary())) return false;
        return model() != null;
    }

    /**
     * Asks the model which cards could apply to the notice.
     *
     * @param cards      the visible catalogue (catalogue order)
     * @param noticeType the detected notice type
     * @param noticeText the notice text (cut to {@code drafting.ocr-excerpt-chars})
     * @param budget     per-notice call / cost limits (null = none)
     * @return the picks, or null when the call failed, returned unusable output twice, was refused, or the budget
     *         refused it
     */
    public Picks pickCards(List<IssueCard> cards, String noticeType, String noticeText, UUID noticeId, NoticeCallBudget budget) {
        Map<String, IssueCard> byId = CatalogueService.byId(cards);
        if (byId.isEmpty() || !available()) return null;
        String model = model();
        String label = "ShortlistTitleAgent (" + byId.size() + " titles)";
        String systemPrompt = systemPrompt(cards);
        String userPrompt = userPrompt(noticeType, noticeText);
        try {
            LlmProvider llm = llmFactory.getLlmForAgent(AGENT_KEY);
            // the same notice must give the same shortlist: temperature 0 where the provider / model takes one
            // (a thinking model keeps its effort setting; the cap below still bounds the list)
            llm = Objects.requireNonNullElse(llm.withTemperature(0.0), llm);
            Picks picks = WriterCall.run(llm, apiUsageLogService, properties, AGENT_KEY, label, model, systemPrompt, null,
                    userPrompt, noticeId, budget,
                    response -> {
                        Parsed parsed = cap(parseTolerant(response.content(), byId.keySet()));
                        return new Picks(parsed.ids(), response.model() != null ? response.model() : model, parsed.fallback(),
                                parsed.returnedCount());
                    }, true);
            if (picks == null) {
                log.warn("{} produced no usable output; shortlist by labels only", label);
            } else {
                log.info("{} named {} card(s), {} kept{}", label, picks.returnedCount(), picks.cardIds().size(),
                        picks.parseFallback() ? " (ids taken from unparseable JSON)" : "");
            }
            return picks;
        } catch (LlmFatalException e) {
            throw e;   // billing / auth error or live run aborted: never a silent fallback
        } catch (Exception e) {
            log.error("{} execution failed: {}", label, e.getMessage());
            return null;
        }
    }

    /** The instructions followed by one {@code NDK-001 | <title>} line per card (latest version, card id order). */
    String systemPrompt(List<IssueCard> cards) {
        List<IssueCard> sorted = new ArrayList<>(CatalogueService.byId(cards).values());
        sorted.sort(Comparator.comparing(IssueCard::cardId));
        StringBuilder sb = new StringBuilder(loadPrompt().stripTrailing()).append('\n');
        for (IssueCard c : sorted) {
            sb.append(c.cardId()).append(" | ").append(oneLine(c.title())).append('\n');
        }
        return sb.toString();
    }

    String userPrompt(String noticeType, String noticeText) {
        return "NOTICE TYPE: " + (noticeType != null ? noticeType : "unknown") + "\n\nDOCUMENT TEXT:\n" + capOcr(noticeText);
    }

    private static String oneLine(String title) {
        return title == null ? "" : title.replaceAll("\\s+", " ").trim();
    }

    private String capOcr(String ocrText) {
        String text = ocrText != null ? ocrText : "";
        int cap = properties.getDrafting() != null && properties.getDrafting().getOcrExcerptChars() > 0
                ? properties.getDrafting().getOcrExcerptChars() : 20000;
        return text.length() > cap ? text.substring(0, cap) : text;
    }

    /**
     * The card ids of a {@code {"card_ids": [...]}} answer that are in the catalogue (trimmed, model order, no
     * repeats); ids not in the catalogue are dropped. An empty list is a valid answer.
     *
     * @throws IllegalArgumentException (short message, no content) when {@code card_ids} is missing or not an array
     */
    Set<String> parseOrThrow(String json, Set<String> catalogueIds) {
        Map<String, Object> map = AiOutputParser.readJsonObject(objectMapper, json);
        if (!(map.get("card_ids") instanceof List<?> ids)) {
            throw new IllegalArgumentException("'card_ids' is missing or not an array (keys present: " + new TreeSet<>(map.keySet()) + ")");
        }
        Set<String> out = new LinkedHashSet<>();
        int dropped = 0;
        for (Object o : ids) {
            String id = o != null ? o.toString().trim() : "";
            if (catalogueIds.contains(id)) out.add(id);
            else if (!id.isEmpty()) dropped++;
        }
        if (dropped > 0) log.info("ShortlistTitleAgent: {} id(s) not in the catalogue dropped", dropped);
        return out;
    }

    /**
     * {@link #parseOrThrow}; when the JSON cannot be read (or has no {@code card_ids} array), the catalogue ids that
     * {@link #CARD_ID} finds in the text (first-seen order, no repeats), marked as a fallback.
     *
     * @throws IllegalArgumentException when neither yields a catalogue id (the caller retries once)
     */
    Parsed parseTolerant(String raw, Set<String> catalogueIds) {
        try {
            return new Parsed(parseOrThrow(raw, catalogueIds), false);
        } catch (IllegalArgumentException e) {
            Set<String> found = new LinkedHashSet<>();
            Matcher m = CARD_ID.matcher(raw != null ? raw : "");
            while (m.find()) if (catalogueIds.contains(m.group())) found.add(m.group());
            if (found.isEmpty()) throw e;
            log.warn("ShortlistTitleAgent: JSON not readable ({}); {} catalogue id(s) taken from the text", e.getMessage(), found.size());
            return new Parsed(found, true);
        }
    }

    private String loadPrompt() {
        try {
            ClassPathResource res = new ClassPathResource("prompts/" + PROMPT_VERSION + ".md");
            try (InputStream is = res.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Prompt prompts/" + PROMPT_VERSION + ".md missing from classpath", e);
        }
    }
}
