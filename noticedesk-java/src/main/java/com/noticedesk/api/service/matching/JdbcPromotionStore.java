package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.service.matching.ProvisionalTemplateService.AiSection;
import com.noticedesk.api.service.matching.ProvisionalTemplateService.IssueMatch;
import com.noticedesk.api.service.matching.ProvisionalTemplateService.NoticeFacts;
import com.noticedesk.api.service.matching.ProvisionalTemplateService.Promotion;
import com.noticedesk.api.service.matching.ProvisionalTemplateService.Provenance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@link ProvisionalTemplateService.Store} on the application database (RLS applies to ai_issue_sections / issue_matches / notices). */
@Component
@RequiredArgsConstructor
@Slf4j
public class JdbcPromotionStore implements ProvisionalTemplateService.Store {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public AiSection loadSection(UUID aiSectionId, UUID tenantId) {
        String sql = """
                SELECT ai_section_id, tenant_id, notice_id, draft_id, issue_no, model, prompt_version, output::text AS output,
                       status, created_at, promoted_card_id, promoted_card_version, promoted_template_id,
                       promoted_template_version, promoted_by, promoted_at
                FROM ai_issue_sections
                WHERE ai_section_id = :id
                """ + (tenantId != null ? " AND tenant_id = :tid" : "");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", aiSectionId);
        if (tenantId != null) p.addValue("tid", tenantId);
        List<AiSection> rows = jdbc.query(sql, p, (rs, i) -> {
            Promotion promotion = null;
            if (rs.getString("promoted_template_id") != null) {
                promotion = new Promotion(rs.getString("promoted_card_id"), rs.getInt("promoted_card_version"),
                        rs.getString("promoted_template_id"), rs.getInt("promoted_template_version"),
                        rs.getString("promoted_by"), rs.getObject("promoted_at", OffsetDateTime.class));
            }
            return new AiSection(rs.getObject("ai_section_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getObject("notice_id", UUID.class), rs.getObject("draft_id", UUID.class), rs.getInt("issue_no"),
                    rs.getString("model"), rs.getString("prompt_version"), readMap(rs.getString("output")),
                    rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class), promotion);
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public IssueMatch loadIssueMatch(UUID noticeId, int issueNo) {
        List<IssueMatch> rows = jdbc.query("""
                SELECT status, card_ids::text AS card_ids, facts::text AS facts, why
                FROM issue_matches
                WHERE notice_id = :nid AND issue_no = :no
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource().addValue("nid", noticeId).addValue("no", issueNo),
                (rs, i) -> new IssueMatch(rs.getString("status"), readList(rs.getString("card_ids")),
                        readMap(rs.getString("facts")), rs.getString("why")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public NoticeFacts loadNotice(UUID noticeId) {
        List<NoticeFacts> rows = jdbc.query("""
                SELECT n.notice_number, n.din_or_rfn, n.issue_date, n.financial_year, n.document_type,
                       n.raw_extracted_json->>'section' AS section,
                       COALESCE(n.raw_extracted_json->>'total_demand_amount', n.raw_extracted_json->>'total_demand',
                                n.raw_extracted_json->>'demand_amount') AS total_demand,
                       c.legal_name, c.trade_name, r.identifier_value
                FROM notices n
                JOIN clients c ON c.client_id = n.client_id
                LEFT JOIN client_registrations r ON r.registration_id = n.registration_id
                WHERE n.notice_id = :nid
                """, new MapSqlParameterSource().addValue("nid", noticeId), (rs, i) -> {
            Date issueDate = rs.getDate("issue_date");
            BigDecimal demand = null;
            String d = rs.getString("total_demand");
            if (d != null) {
                String digits = d.replaceAll("[^0-9.]", "");
                if (!digits.isEmpty() && !digits.equals(".")) {
                    try { demand = new BigDecimal(digits); } catch (NumberFormatException ignored) { /* text demand */ }
                }
            }
            return new NoticeFacts(rs.getString("notice_number"), rs.getString("din_or_rfn"),
                    issueDate != null ? issueDate.toLocalDate() : null, rs.getString("financial_year"),
                    rs.getString("section"), rs.getString("document_type"), rs.getString("legal_name"),
                    rs.getString("trade_name"), rs.getString("identifier_value"), demand);
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public int highestAiCardNumber() {
        Integer n = jdbc.queryForObject("""
                SELECT COALESCE(MAX(CAST(SUBSTRING(card_id FROM :len) AS INT)), 0)
                FROM issue_cards
                WHERE card_id LIKE :prefix AND SUBSTRING(card_id FROM :len) ~ '^[0-9]+$'
                """, new MapSqlParameterSource()
                        .addValue("len", ProvisionalTemplateService.AI_CARD_PREFIX.length() + 1)
                        .addValue("prefix", ProvisionalTemplateService.AI_CARD_PREFIX + "%"), Integer.class);
        return n != null ? n : 0;
    }

    @Override
    public int highestCardVersion(String cardId) {
        Integer v = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM issue_cards WHERE card_id = :id",
                new MapSqlParameterSource("id", cardId), Integer.class);
        return v != null ? v : 0;
    }

    @Override
    public int highestTemplateVersion(String templateId) {
        Integer v = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM reply_templates WHERE template_id = :id",
                new MapSqlParameterSource("id", templateId), Integer.class);
        return v != null ? v : 0;
    }

    @Override
    public void insertCard(IssueCard card, Provenance p) {
        jdbc.update("""
                INSERT INTO issue_cards (card_id, version, title, summary, sections, period_from, period_to, stages, keywords,
                                         not_this_if, template_id, status, reviewed_by, reviewed_at,
                                         source_ai_section_id, source_notice_id, source_tenant_id)
                VALUES (:cardId, :version, :title, :summary, CAST(:sections AS JSONB), :periodFrom, :periodTo, CAST(:stages AS JSONB),
                        CAST(:keywords AS JSONB), CAST(:notThisIf AS JSONB), :templateId, :status, :reviewedBy, :reviewedAt,
                        :sourceSection, :sourceNotice, :sourceTenant)
                """, new MapSqlParameterSource()
                .addValue("cardId", card.cardId()).addValue("version", card.version())
                .addValue("title", card.title()).addValue("summary", card.summary())
                .addValue("sections", json(card.sections())).addValue("periodFrom", card.periodFrom()).addValue("periodTo", card.periodTo())
                .addValue("stages", json(card.stages())).addValue("keywords", json(card.keywords())).addValue("notThisIf", json(card.notThisIf()))
                .addValue("templateId", card.templateId()).addValue("status", card.status())
                .addValue("reviewedBy", p.approvedBy()).addValue("reviewedAt", p.approvedAt())
                .addValue("sourceSection", p.aiSectionId()).addValue("sourceNotice", p.noticeId()).addValue("sourceTenant", p.tenantId()));
    }

    @Override
    public void insertTemplate(ReplyTemplate t, Provenance p) {
        jdbc.update("""
                INSERT INTO reply_templates (template_id, version, card_id, effective_from, effective_to, status, blocks, variables,
                                             documents, summary_line, procedural_objection_options, approved_by, approved_at,
                                             source_ai_section_id, source_notice_id, source_tenant_id)
                VALUES (:templateId, :version, :cardId, :effFrom, :effTo, :status, CAST(:blocks AS JSONB), CAST(:variables AS JSONB),
                        CAST(:documents AS JSONB), :summaryLine, CAST(:options AS JSONB), :approvedBy, :approvedAt,
                        :sourceSection, :sourceNotice, :sourceTenant)
                """, new MapSqlParameterSource()
                .addValue("templateId", t.templateId()).addValue("version", t.version()).addValue("cardId", t.cardId())
                .addValue("effFrom", t.effectiveFrom()).addValue("effTo", t.effectiveTo()).addValue("status", t.status())
                .addValue("blocks", json(t.blocks())).addValue("variables", json(t.variables())).addValue("documents", json(t.documents()))
                .addValue("summaryLine", t.summaryLine()).addValue("options", json(t.proceduralObjectionOptions()))
                .addValue("approvedBy", p.approvedBy()).addValue("approvedAt", p.approvedAt())
                .addValue("sourceSection", p.aiSectionId()).addValue("sourceNotice", p.noticeId()).addValue("sourceTenant", p.tenantId()));
    }

    @Override
    public void markPromoted(UUID aiSectionId, Promotion promotion) {
        jdbc.update("""
                UPDATE ai_issue_sections
                SET status = :status, promoted_card_id = :cardId, promoted_card_version = :cardVersion,
                    promoted_template_id = :templateId, promoted_template_version = :templateVersion,
                    promoted_by = :by, promoted_at = :at, updated_at = NOW()
                WHERE ai_section_id = :id
                """, new MapSqlParameterSource()
                .addValue("id", aiSectionId).addValue("status", ProvisionalTemplateService.SECTION_STATUS_PROMOTED)
                .addValue("cardId", promotion.cardId()).addValue("cardVersion", promotion.cardVersion())
                .addValue("templateId", promotion.templateId()).addValue("templateVersion", promotion.templateVersion())
                .addValue("by", promotion.promotedBy()).addValue("at", promotion.promotedAt()));
    }

    /** Sections of a notice with their promotion status (GET /api/v7/ai-sections). */
    public List<Map<String, Object>> listSections(UUID noticeId, UUID tenantId) {
        return jdbc.queryForList("""
                SELECT ai_section_id, notice_id, draft_id, issue_no, model, prompt_version, status, created_at,
                       promoted_card_id, promoted_card_version, promoted_template_id, promoted_template_version,
                       promoted_by, promoted_at
                FROM ai_issue_sections
                WHERE notice_id = :nid AND tenant_id = :tid
                ORDER BY created_at DESC, issue_no ASC
                """, new MapSqlParameterSource().addValue("nid", noticeId).addValue("tid", tenantId));
    }

    private String json(Object v) {
        try {
            return objectMapper.writeValueAsString(v != null ? v : List.of());
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise catalogue JSON", e);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("unreadable JSON object in catalogue row: {}", e.getMessage());
            return Map.of();
        }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
