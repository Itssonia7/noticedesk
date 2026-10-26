package com.noticedesk.api.service.learned;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.service.matching.PeriodNormalizer;
import com.noticedesk.api.service.matching.ProvisionalTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The learned library: issues the new-issue writer drafted with no catalogue card, kept SEPARATE from the issue card
 * catalogue in {@code $PRIVATE_SEED_DIR/learned/*.json} (one {@link LearnedEntry} per file). Nothing is written when
 * {@code PRIVATE_SEED_DIR} is unset, and never inside a git working tree (the entries hold notice text).
 *
 * <ul>
 *   <li>Write: the v7 pipeline calls {@link #record} for every qualifying issue ({@link #qualifies}); the entry is
 *       "provisional".</li>
 *   <li>Load: {@link #load} (startup with the private-seed profile, see {@code LearnedLibraryLoader}) reads every file
 *       into memory. The entries are not part of {@code CatalogueService.getActiveCards()}.</li>
 *   <li>Use: with {@code catalogue.include-learned=true} the APPROVED entries are rendered to the matcher as extra
 *       cards ({@link #cardsForMatching}) and their text is a provisional reply template of its own
 *       ({@link #templateFor}); provisional entries never reach the matcher.</li>
 *   <li>Approve: {@link #approve} (LearnedLibraryTool) sets status "approved" and approved_by, and writes the file back.</li>
 * </ul>
 * Logs carry entry ids, issue numbers and counts only.
 */
@Service
@Slf4j
public class LearnedLibrary {

    public static final String DIR_NAME = "learned";
    public static final String ID_PREFIX = "LRN-";
    /** Note on the cards / templates built from an entry. */
    static final String NOT_THIS_IF = "Learned from one notice; confirm the facts match before reuse";

    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern CITED_PROVISION = Pattern.compile(
            "(?i)\\b(Section|Rule)\\s+(\\d+[A-Z]{0,2}(?:\\s*\\(\\s*[0-9A-Za-z]+\\s*\\))*)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z(\\[])");

    private final AppProperties properties;
    private final ObjectMapper writer;
    private final ObjectMapper reader;
    private final List<LearnedEntry> entries = new CopyOnWriteArrayList<>();
    private final Map<String, File> files = new LinkedHashMap<>();
    private Clock clock = Clock.systemDefaultZone();
    private String sourceRun;

    public LearnedLibrary(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        ObjectMapper base = objectMapper != null ? objectMapper : new ObjectMapper();
        this.reader = base;
        this.writer = base.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** The run id written on new entries (the live runners set the commit sha). */
    public void setSourceRun(String sourceRun) {
        this.sourceRun = sourceRun;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public String sourceRun() {
        if (sourceRun != null && !sourceRun.isBlank()) return sourceRun.trim();
        String configured = properties != null && properties.getCatalogue() != null ? properties.getCatalogue().getLearnedSourceRun() : null;
        return configured != null && !configured.isBlank() ? configured.trim() : "unknown";
    }

    /** {@code catalogue.include-learned}: approved entries reach the matcher (default false). */
    public boolean includeLearned() {
        return properties != null && properties.getCatalogue() != null && properties.getCatalogue().isIncludeLearned();
    }

    /** {@code $PRIVATE_SEED_DIR/learned}, or null when PRIVATE_SEED_DIR is unset. */
    public File dir() {
        String root = properties != null ? properties.getPrivateSeedDir() : null;
        if (root == null || root.isBlank()) return null;
        return new File(root.trim(), DIR_NAME);
    }

    // ---------------------------------------------------------------- load

    public record Counts(int total, int provisional, int approved) {
        public String line() {
            return "learned library: " + total + " entries (" + provisional + " provisional, " + approved + " approved)";
        }
    }

    /** Reads every {@code learned/*.json} into memory (replacing what was loaded); logs the count line. */
    public synchronized Counts load() {
        entries.clear();
        files.clear();
        File dir = dir();
        File[] list = dir != null ? dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json")) : null;
        if (list != null) {
            Arrays.sort(list);
            for (int i = 0; i < list.length; i++) {
                try {
                    LearnedEntry e = reader.readValue(list[i], LearnedEntry.class);
                    if (e.entryId() == null || e.entryId().isBlank()) {
                        log.warn("learned library: file #{} has no entry_id; skipped", i + 1);
                        continue;
                    }
                    entries.add(e);
                    files.put(e.entryId(), list[i]);
                } catch (IOException ex) {
                    // the message can quote file content: report the number and type only
                    log.warn("learned library: file #{} is not a valid entry ({}); skipped", i + 1, ex.getClass().getSimpleName());
                }
            }
        }
        Counts c = counts();
        log.info(c.line());
        return c;
    }

    public Counts counts() {
        int approved = (int) entries.stream().filter(LearnedEntry::isApproved).count();
        return new Counts(entries.size(), entries.size() - approved, approved);
    }

    public List<LearnedEntry> entries() {
        return List.copyOf(entries);
    }

    public Optional<LearnedEntry> find(String entryId) {
        if (entryId == null) return Optional.empty();
        return entries.stream().filter(e -> entryId.trim().equalsIgnoreCase(e.entryId())).findFirst();
    }

    // ---------------------------------------------------------------- write

    /**
     * True when the issue's AI section is a learned-library candidate: written by the new-issue writer (plain or
     * high-stakes tier) for an issue with status none and no catalogue card, and not a placeholder (a skipped
     * no-substantive-ground issue, a code defect or a refused call is a placeholder).
     */
    public static boolean qualifies(MatchedIssue issue, String writer, boolean placeholder) {
        if (placeholder || issue == null) return false;
        if (!"none".equalsIgnoreCase(issue.status()) || issue.primaryCardId() != null) return false;
        return "new_issue".equals(writer) || "high_stakes".equals(writer);
    }

    /** Context of one pipeline run for {@link #record}. */
    public record RunContext(String noticeId, String documentType, String stage, String stageTemplate,
                             Map<String, Object> noticeFacts) {}

    /**
     * Builds and saves the entry for one qualifying issue. Returns the saved entry, or empty when PRIVATE_SEED_DIR is
     * unset, the folder is inside a git working tree, or the write failed (the draft is never affected).
     */
    public Optional<LearnedEntry> record(RunContext run, MatchedIssue issue, Map<String, Object> aiOutput, String writerProviderModel,
                                         String promptVersion) {
        if (dir() == null) return Optional.empty();
        LearnedEntry entry = build(run, issue, aiOutput, writerProviderModel, promptVersion, sourceRun(), OffsetDateTime.now(clock));
        return save(entry);
    }

    /** Pure conversion of a pipeline issue into a provisional entry with a fresh LRN id. */
    @SuppressWarnings("unchecked")
    public static LearnedEntry build(RunContext run, MatchedIssue issue, Map<String, Object> aiOutput, String writerProviderModel,
                                     String promptVersion, String sourceRun, OffsetDateTime now) {
        Map<String, Object> out = aiOutput != null ? aiOutput : Map.of();
        Map<String, Object> facts = issue.facts() != null ? issue.facts() : Map.of();
        StringBuilder s04 = new StringBuilder();
        StringBuilder s06 = new StringBuilder();
        if (out.get("sections") instanceof Collection<?> secs) {
            for (Object o : secs) {
                if (!(o instanceof Map<?, ?> m)) continue;
                int sec = m.get("section") instanceof Number n ? n.intValue() : -1;
                String html = Objects.toString(m.get("html"), "");
                if (sec == 4) s04.append(html);
                else if (sec == 6) s06.append(html);
            }
        }
        List<String> documents = new ArrayList<>();
        if (out.get("documents") instanceof Collection<?> docs) for (Object d : docs) if (d != null) documents.add(d.toString());
        List<Map<String, Object>> citations = new ArrayList<>();
        if (out.get("citations") instanceof Collection<?> cits) {
            for (Object o : cits) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("case", m.get("case"));
                c.put("court", m.get("court"));
                c.put("year", m.get("year"));
                c.put("cited_for", m.get("cited_for"));
                c.put("check_status", m.get("check_status") != null ? m.get("check_status") : "NOT_CHECKED");
                citations.add(c);
            }
        }
        return new LearnedEntry(newId(), run.noticeId(), issue.issueNo(), now.toString(), run.documentType(), run.stage(),
                run.stageTemplate(), groundOf(issue), text(facts.get("legal_section")), facts.get("amount"), text(facts.get("period")),
                s04.isEmpty() ? null : s04.toString(), s06.isEmpty() ? null : s06.toString(), documents,
                text(out.get("summary_line")), citations, writerProviderModel, promptVersion,
                run.noticeFacts() != null ? new LinkedHashMap<>(run.noticeFacts()) : Map.of(),
                LearnedEntry.STATUS_PROVISIONAL, null, null, sourceRun);
    }

    /**
     * Writes the entry to {@code learned/<yyyy-mm-dd>_<notice-id>_issue<N>.json}. A provisional entry already in that
     * file (a re-run the same day) is replaced and keeps its id; an approved one is never overwritten (the new entry
     * goes to {@code ..._issue<N>_2.json}, {@code _3}, ...).
     */
    public synchronized Optional<LearnedEntry> save(LearnedEntry entry) {
        File dir = dir();
        if (dir == null) return Optional.empty();
        if (insideGitWorkTree(dir)) {
            log.warn("learned library: {} is inside a git working tree; entry for issue #{} not written", DIR_NAME, entry.issueNo());
            return Optional.empty();
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log.warn("learned library: folder could not be created; entry for issue #{} not written", entry.issueNo());
            return Optional.empty();
        }
        String base = datePart(entry.createdAt()) + "_" + noticeIdForFile(entry.noticeId()) + "_issue" + entry.issueNo();
        LearnedEntry toWrite = entry;
        File target = new File(dir, base + ".json");
        for (int n = 2; target.exists(); n++) {
            LearnedEntry existing = readQuietly(target);
            if (existing == null || !existing.isApproved()) {
                if (existing != null && existing.entryId() != null) {
                    toWrite = entry.withEntryId(existing.entryId());
                    final String oldId = existing.entryId();
                    entries.removeIf(e -> oldId.equals(e.entryId()));
                }
                break;
            }
            target = new File(dir, base + "_" + n + ".json");
        }
        try {
            writer.writeValue(target, toWrite);
        } catch (IOException e) {
            log.warn("learned library: entry {} not written ({})", toWrite.entryId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
        entries.add(toWrite);
        files.put(toWrite.entryId(), target);
        log.info("learned library: saved {} for issue #{} (provisional)", toWrite.entryId(), toWrite.issueNo());
        return Optional.of(toWrite);
    }

    /**
     * Marks the entry approved by {@code approvedBy} and writes its file back.
     *
     * @throws NoSuchElementException when no loaded entry has that id
     */
    public synchronized LearnedEntry approve(String entryId, String approvedBy) {
        if (approvedBy == null || approvedBy.isBlank()) throw new IllegalArgumentException("approved_by is required");
        LearnedEntry e = find(entryId).orElseThrow(() -> new NoSuchElementException("learned entry not found: " + entryId));
        File f = files.get(e.entryId());
        if (f == null) throw new IllegalStateException("learned entry " + e.entryId() + " has no file");
        LearnedEntry approved = e.approved(approvedBy.trim(), OffsetDateTime.now(clock).toString());
        try {
            writer.writeValue(f, approved);
        } catch (IOException ex) {
            throw new IllegalStateException("learned entry " + e.entryId() + " could not be written (" + ex.getClass().getSimpleName() + ")");
        }
        entries.replaceAll(x -> x.entryId().equals(e.entryId()) ? approved : x);
        log.info("learned library: {} approved", e.entryId());
        return approved;
    }

    // ---------------------------------------------------------------- use

    /** The approved entries as matcher cards when {@code catalogue.include-learned} is on; empty otherwise. */
    public List<IssueCard> cardsForMatching() {
        if (!includeLearned()) return List.of();
        List<IssueCard> out = new ArrayList<>();
        for (LearnedEntry e : entries) if (e.isApproved()) out.add(toCard(e));
        out.sort(Comparator.comparing(IssueCard::cardId));
        return out;
    }

    /** The reply template of an approved entry's card when {@code catalogue.include-learned} is on. */
    public Optional<ReplyTemplate> templateFor(String cardId) {
        if (!includeLearned() || cardId == null || !cardId.startsWith(ID_PREFIX)) return Optional.empty();
        return find(cardId).filter(LearnedEntry::isApproved).flatMap(LearnedLibrary::toTemplate);
    }

    /**
     * The matcher card of an entry: id = entry id, title from the ground, summary from the first sentences of the
     * grounds of defence, stages from the response stage, keywords = the sections cited.
     */
    public static IssueCard toCard(LearnedEntry e) {
        String title = plain(e.ground());
        if (title.isEmpty()) title = plain(e.summaryLine());
        if (title.isEmpty()) title = "Learned issue " + e.entryId();
        title = truncate(title, 160);
        String lead = firstSentences(plain(e.groundsOfDefence()), 2, 400);
        String summary = "[LEARNED - AI-drafted for one notice, approved by " + Objects.toString(e.approvedBy(), "nobody") + "] "
                + (lead.isEmpty() ? title : lead);
        List<String> provisions = citedProvisions(e);
        List<String> keywords = provisions.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
        Optional<PeriodNormalizer.Period> period = PeriodNormalizer.parse(e.period());
        return new IssueCard(e.entryId(), 1, title, summary, provisions,
                period.map(p -> p.start().toString()).orElse(null), period.map(p -> p.end().toString()).orElse(null),
                stagesOf(e), keywords, List.of(NOT_THIS_IF), templateId(e), "active", e.approvedBy(),
                parseTime(e.approvedAt()), parseTime(e.createdAt()));
    }

    /**
     * The entry's text as a provisional reply template (sections 04 and 06 split into blocks, the notice's own values
     * replaced by placeholders, documents and summary line carried over); empty when it has no text to reuse.
     */
    public static Optional<ReplyTemplate> toTemplate(LearnedEntry e) {
        List<Map<String, Object>> secs = new ArrayList<>();
        if (e.groundsOfDefence() != null && !e.groundsOfDefence().isBlank()) secs.add(Map.of("section", 4, "html", e.groundsOfDefence()));
        if (e.legalSubmissions() != null && !e.legalSubmissions().isBlank()) secs.add(Map.of("section", 6, "html", e.legalSubmissions()));
        if (secs.isEmpty()) return Optional.empty();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("sections", secs);
        output.put("citations", e.citations());
        output.put("documents", e.documents());
        if (e.summaryLine() != null) output.put("summary_line", e.summaryLine());
        Map<String, Object> facts = new LinkedHashMap<>();
        if (e.amount() != null) facts.put("amount", e.amount());
        if (e.period() != null) facts.put("period", e.period());
        if (e.legalSection() != null) facts.put("legal_section", e.legalSection());
        Map<String, Object> nf = e.noticeFacts();
        ProvisionalTemplateService.NoticeFacts notice = new ProvisionalTemplateService.NoticeFacts(
                text(nf.get("notice_number")), null, null, null, e.legalSection(), e.documentType(),
                text(nf.get("client_name")), null, text(nf.get("gstin")), null);
        UUID sourceId = UUID.nameUUIDFromBytes(e.entryId().getBytes(StandardCharsets.UTF_8));
        ProvisionalTemplateService.AiSection section = new ProvisionalTemplateService.AiSection(sourceId, null, null, null,
                e.issueNo(), Objects.toString(e.writer(), "unknown"), Objects.toString(e.promptVersion(), "unknown"), output,
                e.status(), parseTime(e.createdAt()), null);
        OffsetDateTime at = Optional.ofNullable(parseTime(e.approvedAt())).orElse(parseTime(e.createdAt()));
        try {
            ProvisionalTemplateService.Built built = ProvisionalTemplateService.build(section,
                    new ProvisionalTemplateService.IssueMatch("none", List.of(), facts, null), notice, e.entryId(), 1,
                    templateId(e), 1, new ProvisionalTemplateService.Provenance(sourceId, null, null, e.approvedBy(), at));
            return Optional.of(built.template());
        } catch (IllegalStateException ex) {
            log.warn("learned library: {} has no reusable text ({})", e.entryId(), ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- helpers

    static String templateId(LearnedEntry e) {
        return "TPL-" + e.entryId();
    }

    static List<String> stagesOf(LearnedEntry e) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if ("appeal".equalsIgnoreCase(e.stage())) out.add("order_appeal");
        if (e.stageTemplate() != null && !e.stageTemplate().isBlank() && !"unknown".equalsIgnoreCase(e.stageTemplate())) {
            out.add(e.stageTemplate().trim());
        }
        return new ArrayList<>(out);
    }

    /** "Section 73(1)", "Rule 142" ... named by the legal section and the entry's text, in order, without repeats. */
    static List<String> citedProvisions(LearnedEntry e) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : new String[]{e.legalSection(), plain(e.groundsOfDefence()), plain(e.legalSubmissions())}) {
            if (t == null) continue;
            Matcher m = CITED_PROVISION.matcher(t);
            while (m.find()) {
                String kind = m.group(1).substring(0, 1).toUpperCase(Locale.ROOT) + m.group(1).substring(1).toLowerCase(Locale.ROOT);
                out.add(kind + " " + m.group(2).replaceAll("\\s+", ""));
            }
        }
        return new ArrayList<>(out);
    }

    static String firstSentences(String text, int n, int max) {
        if (text == null || text.isBlank()) return "";
        String[] parts = SENTENCE_END.split(text.trim());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < n; i++) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(parts[i].trim());
        }
        return truncate(sb.toString(), max);
    }

    /** The matcher's ground text: facts ground / label / title / issue / allegation, else its reason. */
    static String groundOf(MatchedIssue issue) {
        Map<String, Object> facts = issue.facts() != null ? issue.facts() : Map.of();
        for (String k : List.of("ground", "label", "title", "issue", "allegation")) {
            String v = text(facts.get(k));
            if (v != null) return v;
        }
        return text(issue.why());
    }

    /** Notice id as used in file names: lower-case, every run of non-alphanumerics replaced by '-'. */
    public static String noticeIdForFile(String noticeId) {
        String s = noticeId == null ? "" : noticeId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "notice" : s;
    }

    static String newId() {
        return ID_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** True when {@code dir} or one of its parents holds a {@code .git} entry (a repository working tree). */
    static boolean insideGitWorkTree(File dir) {
        for (File f = dir.getAbsoluteFile(); f != null; f = f.getParentFile()) {
            if (new File(f, ".git").exists()) return true;
        }
        return false;
    }

    private LearnedEntry readQuietly(File f) {
        try {
            return reader.readValue(f, LearnedEntry.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static String datePart(String createdAt) {
        OffsetDateTime t = parseTime(createdAt);
        return (t != null ? t.toLocalDate() : LocalDate.now()).toString();
    }

    private static OffsetDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String plain(String html) {
        if (html == null) return "";
        return TAGS.matcher(html).replaceAll(" ").replace("&amp;", "&").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String text(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3).trim() + "...";
    }
}
