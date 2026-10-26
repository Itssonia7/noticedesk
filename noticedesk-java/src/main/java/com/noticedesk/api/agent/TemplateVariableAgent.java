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

/**
 * v7 Stage 1b: one call per notice, after matching and template selection and before template fill, that copies
 * the values of card-specific template variables (for example {@code issue.return_filing_date},
 * {@code issue.order_date}) out of the notice text. The matcher extracts only the generic facts (period, amount,
 * legal section), so without this step those placeholders stayed {@code [[MISSING: ...]]}.
 *
 * <p>Runs on the partial-drafting model ({@code LLM_MODEL_PARTIAL_DRAFTING}) with the
 * {@code template_variables} agent key (effort {@code LLM_EFFORT_TEMPLATE_VARIABLES}, max_tokens
 * {@code LLM_MAX_TOKENS_TEMPLATE_VARIABLES}); the call goes through {@link WriterCall} like the other Stage 3
 * writers (budget pre-check, usage logging, one retry on unusable output, {@link LlmFatalException} passed on).
 * The parser ignores variable names that were not requested and never invents a value: a variable the document
 * does not state stays a {@code [[MISSING: ...]]} marker in the draft.
 */
@Service
@Slf4j
public class TemplateVariableAgent {

    public static final String PROMPT_VERSION = "template_variables_v1";
    /** Agent key for effort / max_tokens / budget lines ({@code step=template_variables} in the call log). */
    public static final String AGENT_KEY = AppProperties.Llm.TEMPLATE_VARIABLES;

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ApiUsageLogService apiUsageLogService;
    private final LlmFactory llmFactory;
    private final CatalogueService catalogueService;

    @Autowired
    public TemplateVariableAgent(AppProperties properties, ObjectMapper objectMapper, ApiUsageLogService apiUsageLogService,
                                 LlmFactory llmFactory, CatalogueService catalogueService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.apiUsageLogService = apiUsageLogService;
        this.llmFactory = llmFactory;
        this.catalogueService = catalogueService;
    }

    /** Without a catalogue (tests): card titles are taken from the requests as given. */
    public TemplateVariableAgent(AppProperties properties, ObjectMapper objectMapper, ApiUsageLogService apiUsageLogService,
                                 LlmFactory llmFactory) {
        this(properties, objectMapper, apiUsageLogService, llmFactory, null);
    }

    /** One template variable still unknown for an issue, with a one-line hint derived from its name. */
    public record VariableSpec(String name, String hint) {}

    /**
     * The unknown variables of one matched issue that has a template. {@code cardTitle} may be null; the agent then
     * looks the title up in the active catalogue by {@code cardId}.
     */
    public record IssueVariableRequest(int issueNo, String cardId, String cardTitle, List<VariableSpec> variables) {
        public IssueVariableRequest {
            if (variables == null) variables = List.of();
        }

        /** The requested variable names, in order. */
        public List<String> variableNames() {
            return variables.stream().map(VariableSpec::name).toList();
        }
    }

    /**
     * @param values   per issue number, the variables the document states (name as requested -> value as stated)
     * @param notFound requested variable names the model reported as not stated in the document
     */
    public record TemplateVariablesResult(Map<Integer, Map<String, String>> values, List<String> notFound, String model,
                                          String promptVersion) {
        public TemplateVariablesResult {
            if (values == null) values = Map.of();
            if (notFound == null) notFound = List.of();
        }

        public Map<String, String> valuesFor(int issueNo) {
            return values.getOrDefault(issueNo, Map.of());
        }

        public int valueCount() {
            int n = 0;
            for (Map<String, String> m : values.values()) n += m.size();
            return n;
        }
    }

    /**
     * Asks the model for the values of every requested variable, once for the whole notice.
     *
     * @param ocrText  the notice text (cut to {@code drafting.ocr-excerpt-chars})
     * @param requests one entry per issue with at least one unknown variable (the caller skips the call when empty)
     * @param budget   per-notice call / cost limits (null = none)
     * @return the parsed values, or {@code null} when the call failed, returned unusable output twice, or the
     *         budget refused it (every placeholder then stays {@code [[MISSING: ...]]})
     */
    public TemplateVariablesResult extractVariables(String ocrText, List<IssueVariableRequest> requests, UUID noticeId,
                                                    NoticeCallBudget budget) {
        if (requests == null || requests.isEmpty()) {
            return new TemplateVariablesResult(Map.of(), List.of(), null, PROMPT_VERSION);
        }
        String label = "TemplateVariableAgent (" + requests.size() + " issue(s))";
        String model = properties.getLlm() != null ? properties.getLlm().getModelPartialDrafting() : null;
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("LLM_MODEL_PARTIAL_DRAFTING is not configured. Set environment variable LLM_MODEL_PARTIAL_DRAFTING.");
        }

        String provider = properties.getLlm().getProviderPrimary();
        if ("stub".equalsIgnoreCase(provider) || llmFactory == null) {
            log.info("{} running in stub mode: no variable values", label);
            List<String> all = new ArrayList<>();
            for (IssueVariableRequest r : requests) all.addAll(r.variableNames());
            return new TemplateVariablesResult(Map.of(), List.copyOf(all), "stub", PROMPT_VERSION);
        }

        String systemPrompt = loadPrompt();
        String userPrompt = buildUserPrompt(ocrText, requests);

        try {
            // the partial-drafting model answers this step: same provider instance, same price list
            LlmProvider llm = llmFactory.getLlmForAgent("partial_drafting");
            TemplateVariablesResult result = WriterCall.run(llm, apiUsageLogService, properties, AGENT_KEY, label, model,
                    systemPrompt, null, userPrompt, noticeId, budget,
                    response -> parseOrThrow(response.content(), response.model() != null ? response.model() : model, requests));
            if (result == null) {
                log.warn("{} produced no usable output", label);
            }
            return result;
        } catch (LlmFatalException e) {
            throw e;   // billing / auth error or live run aborted: never a silent fallback
        } catch (Exception e) {
            log.error("{} execution failed: {}", label, e.getMessage());
            return null;
        }
    }

    /** The JSON the model receives: the capped document text and, per issue, the card title and the unknown variables. */
    String buildUserPrompt(String ocrText, List<IssueVariableRequest> requests) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_text", capOcr(ocrText));
        List<Map<String, Object>> issues = new ArrayList<>();
        for (IssueVariableRequest r : requests) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("issue_no", r.issueNo());
            m.put("card_title", r.cardTitle() != null ? r.cardTitle() : cardTitle(r.cardId()));
            List<Map<String, String>> vars = new ArrayList<>();
            for (VariableSpec v : r.variables()) {
                Map<String, String> vm = new LinkedHashMap<>();
                vm.put("name", v.name());
                vm.put("hint", v.hint() != null ? v.hint() : hintFor(v.name()));
                vars.add(vm);
            }
            m.put("variables", vars);
            issues.add(m);
        }
        payload.put("issues", issues);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return payload.toString();
        }
    }

    private String capOcr(String ocrText) {
        String text = ocrText != null ? ocrText : "";
        int cap = properties.getDrafting() != null && properties.getDrafting().getOcrExcerptChars() > 0
                ? properties.getDrafting().getOcrExcerptChars() : 20000;
        return text.length() > cap ? text.substring(0, cap) : text;
    }

    private String cardTitle(String cardId) {
        if (cardId == null || catalogueService == null) return cardId;
        try {
            for (IssueCard c : CatalogueService.withLearned(catalogueService.getActiveCards(), catalogueService.learnedCardsForMatching())) {
                if (cardId.equals(c.cardId())) return c.title() != null && !c.title().isBlank() ? c.title() : cardId;
            }
        } catch (Exception e) {
            log.debug("card title lookup failed for {}: {}", cardId, e.getMessage());
        }
        return cardId;
    }

    /**
     * One-line meaning of a template variable derived from its name: the words of the name after its prefix, plus
     * the value shape the name implies ("date" -> dd.mm.yyyy; amount / tax / interest / penalty / turnover / demand /
     * fee -> digits; "period" -> as written).
     */
    public static String hintFor(String variableName) {
        String n = variableName != null ? variableName.trim() : "";
        int dot = n.indexOf('.');
        String bare = dot >= 0 ? n.substring(dot + 1) : n;
        String words = bare.replace('_', ' ').replace('.', ' ').trim();
        if (words.isEmpty()) words = n;
        String lower = bare.toLowerCase(Locale.ROOT);
        String shape;
        if (lower.contains("date")) {
            shape = "a date stated in the document, written dd.mm.yyyy";
        } else if (lower.contains("amount") || lower.contains("tax") || lower.contains("interest") || lower.contains("penalty")
                || lower.contains("turnover") || lower.contains("demand") || lower.contains("fee")) {
            shape = "an amount in rupees stated in the document, digits only";
        } else if (lower.contains("period") || lower.contains("year")) {
            shape = "a tax period or financial year as the document writes it";
        } else {
            shape = "copied exactly as the document states it";
        }
        return "the " + words + " (" + shape + ")";
    }

    /**
     * Parses the model output. Variable names that were not requested for that issue are ignored; blank values are
     * ignored; numbers are rendered as digits.
     *
     * @throws IllegalArgumentException (short message, no content) when the output has neither an {@code issues}
     *         array nor a {@code not_found} array
     */
    TemplateVariablesResult parseOrThrow(String json, String model, List<IssueVariableRequest> requests) {
        Map<String, Object> map = AiOutputParser.readJsonObject(objectMapper, json);
        boolean hasIssues = map.get("issues") instanceof List<?>;
        boolean hasNotFound = map.get("not_found") instanceof List<?>;
        if (!hasIssues && !hasNotFound) {
            throw new IllegalArgumentException("'issues' and 'not_found' are both missing or not arrays (keys present: "
                    + new TreeSet<>(map.keySet()) + ")");
        }

        Map<Integer, Set<String>> requested = new HashMap<>();
        for (IssueVariableRequest r : requests) {
            requested.computeIfAbsent(r.issueNo(), k -> new LinkedHashSet<>()).addAll(r.variableNames());
        }

        Map<Integer, Map<String, String>> values = new LinkedHashMap<>();
        int ignored = 0;
        if (hasIssues) {
            for (Object item : (List<?>) map.get("issues")) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Integer issueNo = intOrNull(m.get("issue_no"));
                if (issueNo == null || !requested.containsKey(issueNo)) {
                    ignored++;
                    continue;
                }
                if (!(m.get("variables") instanceof Map<?, ?> vars)) continue;
                Set<String> allowed = requested.get(issueNo);
                for (Map.Entry<?, ?> e : vars.entrySet()) {
                    String name = e.getKey() != null ? e.getKey().toString().trim() : "";
                    String value = valueText(e.getValue());
                    if (!allowed.contains(name)) {
                        ignored++;
                        continue;
                    }
                    if (value == null) continue;
                    values.computeIfAbsent(issueNo, k -> new LinkedHashMap<>()).put(name, value);
                }
            }
        }
        if (ignored > 0) {
            log.info("TemplateVariableAgent: {} value(s) for issues or variables that were not requested ignored", ignored);
        }

        List<String> notFound = new ArrayList<>();
        if (hasNotFound) {
            for (Object o : (List<?>) map.get("not_found")) {
                String s = o != null ? o.toString().trim() : "";
                if (!s.isEmpty() && !notFound.contains(s)) notFound.add(s);
            }
        }
        return new TemplateVariablesResult(Collections.unmodifiableMap(values), List.copyOf(notFound), model, PROMPT_VERSION);
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return null;
        try {
            return Integer.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The value as text, or null when blank / null; whole numbers without a decimal part. */
    static String valueText(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf(n.longValue()) : n.toString();
        }
        String s = v.toString().trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
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
