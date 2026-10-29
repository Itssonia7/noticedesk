package com.noticedesk.api.controller;

import com.noticedesk.api.exception.AppValidationException;
import com.noticedesk.api.exception.NotFoundException;
import com.noticedesk.api.security.TenantContextHolder;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.service.matching.JdbcPromotionStore;
import com.noticedesk.api.service.matching.ProvisionalTemplateService;
import com.noticedesk.api.service.matching.ProvisionalTemplateService.PromotionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * v7 AI issue sections: list a notice's sections with their promotion status, and promote an approved
 * section into a provisional issue card + reply template. Tenant-scoped: the tenant comes from the
 * request's auth claims and is set as the RLS tenant for the transaction, like the /v1 controllers.
 */
@Slf4j
@RestController
@RequestMapping("/api/v7")
@RequiredArgsConstructor
public class AiSectionController {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final ProvisionalTemplateService provisionalTemplateService;
    private final JdbcPromotionStore promotionStore;

    /** Body of POST /api/v7/ai-sections/{id}/promote. */
    public record PromoteRequest(String approvedBy, String cardId) {}

    // ---- GET /api/v7/ai-sections?noticeId=... ----

    @GetMapping("/ai-sections")
    @Transactional(readOnly = true)
    public Map<String, Object> listSections(@RequestParam("noticeId") UUID noticeId) {
        UUID tenantId = tenant();
        List<Map<String, Object>> sections = promotionStore.listSections(noticeId, tenantId);
        List<Map<String, Object>> out = sections.stream().map(AiSectionController::sectionView).toList();
        return Map.of("notice_id", noticeId, "sections", out);
    }

    // ---- POST /api/v7/ai-sections/{id}/promote ----

    @PostMapping("/ai-sections/{id}/promote")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> promote(@PathVariable UUID id, @RequestBody(required = false) PromoteRequest request) {
        UUID tenantId = tenant();
        String userId = TenantContextHolder.getUserId();
        String approvedBy = request != null && request.approvedBy() != null && !request.approvedBy().isBlank()
                ? request.approvedBy().trim() : userId;
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new AppValidationException("approvedBy is required");
        }
        String cardId = request != null && request.cardId() != null && !request.cardId().isBlank() ? request.cardId().trim() : null;

        PromotionResult result;
        try {
            result = provisionalTemplateService.promote(id, approvedBy, cardId, tenantId);
        } catch (NoSuchElementException e) {
            throw new NotFoundException("AI section not found: " + id);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new AppValidationException(e.getMessage());
        }

        if (result.created()) {
            auditService.emit(tenantId.toString(), userId, "ai_section.promoted", "reply_templates",
                    result.templateId() + ":" + result.templateVersion(), null,
                    Map.of("ai_section_id", id.toString(), "card_id", result.cardId(), "card_version", result.cardVersion(),
                            "approved_by", approvedBy), 1);
        }
        log.info("AI section {} promote: created={} card={} v{} template={} v{} tenant={}", id, result.created(),
                result.cardId(), result.cardVersion(), result.templateId(), result.templateVersion(), tenantId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ai_section_id", id);
        body.put("created", result.created());
        body.put("card_id", result.cardId());
        body.put("card_version", result.cardVersion());
        body.put("template_id", result.templateId());
        body.put("template_version", result.templateVersion());
        body.put("template_status", "provisional");
        body.put("variables", result.variables());
        body.put("blocks", result.blocks());
        return body;
    }

    private UUID tenant() {
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new AppValidationException("no tenant in request context");
        }
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)", Map.of("tid", tenantId), String.class);
        return UUID.fromString(tenantId);
    }

    private static Map<String, Object> sectionView(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ai_section_id", row.get("ai_section_id"));
        m.put("notice_id", row.get("notice_id"));
        m.put("draft_id", row.get("draft_id"));
        m.put("issue_no", row.get("issue_no"));
        m.put("model", row.get("model"));
        m.put("prompt_version", row.get("prompt_version"));
        m.put("status", row.get("status"));
        m.put("created_at", row.get("created_at"));
        boolean promoted = row.get("promoted_template_id") != null;
        m.put("promoted", promoted);
        if (promoted) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("card_id", row.get("promoted_card_id"));
            p.put("card_version", row.get("promoted_card_version"));
            p.put("template_id", row.get("promoted_template_id"));
            p.put("template_version", row.get("promoted_template_version"));
            p.put("promoted_by", row.get("promoted_by"));
            p.put("promoted_at", row.get("promoted_at"));
            m.put("promotion", p);
        }
        return m;
    }
}
