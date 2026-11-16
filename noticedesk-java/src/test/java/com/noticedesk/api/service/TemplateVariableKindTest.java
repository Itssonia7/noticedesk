package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.TemplateVariableAgent;
import com.noticedesk.api.agent.TemplateVariableAgent.TemplateVariablesResult;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.layout.ReplyLayout;
import com.noticedesk.api.service.layout.ReplyLayoutText;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import com.noticedesk.api.service.matching.TemplateVariableKinds;
import com.noticedesk.api.service.matching.TemplateVariableKinds.Kind;
import com.noticedesk.api.testsupport.SyntheticS73Notice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Live run at c82df8b on the synthetic Section 73 notice: issue.late_fee_status was "read from the document" as 17800
 * and the reply printed "late fee under Section 47 — which the noticee 17800". A value must fit the variable's kind
 * before it is substituted; one that does not is treated as not found. Offline: the variable agent is mocked.
 */
class TemplateVariableKindTest {

    private static final String BLOCK = "<p>As regards the late fee under Section 47 — which the noticee {{issue.late_fee_status}} —"
            + " the demand is not sustainable.</p>";

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void kindsFollowTheDeclaredTypeElseTheName() {
        for (String n : List.of("issue.late_fee_status", "issue.client_position", "issue.rule86b_exception", "issue.scrip_type",
                "issue.delay_reason", "issue.claim_basis", "issue.limitation_plea", "issue.supply_nature")) {
            assertEquals(Kind.TEXT, TemplateVariableKinds.kindOf(n), n);
        }
        assertEquals(Kind.AMOUNT, TemplateVariableKinds.kindOf("issue.interest_amount"));
        assertEquals(Kind.AMOUNT, TemplateVariableKinds.kindOf("issue.scrip_value"));
        assertEquals(Kind.AMOUNT, TemplateVariableKinds.kindOf("issue.amount_tax"));
        assertEquals(Kind.DATE, TemplateVariableKinds.kindOf("issue.order_date"));
        assertEquals(Kind.IDENTIFIER, TemplateVariableKinds.kindOf("issue.arn_no"));
        assertEquals(Kind.IDENTIFIER, TemplateVariableKinds.kindOf("issue.invoice_number"));
        assertEquals(Kind.ANY, TemplateVariableKinds.kindOf("issue.period"));
        assertEquals(Kind.AMOUNT, TemplateVariableKinds.kindOf("issue.late_fee_status", "amount"), "a declared type wins");

        assertFalse(TemplateVariableKinds.fits("issue.late_fee_status", "17800"));
        assertFalse(TemplateVariableKinds.fits("issue.late_fee_status", "Rs. 17,800/-"));
        assertFalse(TemplateVariableKinds.fits("issue.late_fee_status", 17800));
        assertTrue(TemplateVariableKinds.fits("issue.late_fee_status", "has been paid"));
        assertTrue(TemplateVariableKinds.fits("issue.late_fee_status", "paid Rs 17,800 on 03.11.2021"));
        assertFalse(TemplateVariableKinds.fits("issue.interest_amount", "has been paid"));
        assertTrue(TemplateVariableKinds.fits("issue.interest_amount", "48231"));
        assertEquals("variable rejected: issue.late_fee_status expected text", TemplateVariableKinds.rejectionNote("issue.late_fee_status"));
        assertTrue(TemplateVariableAgent.hintFor("issue.late_fee_status").contains("never a number or amount"),
                "the hint no longer asks for an amount because the name holds 'fee'");
    }

    @Test
    void liveCase_amountReadForAStatus_isRejected_leftAsPlaceholder_andListedToFill() {
        TemplateFillService fill = fill();
        List<MatchedIssue> issues = SyntheticS73Notice.issues();
        List<String> notes = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();

        List<MatchedIssue> merged = pipeline(fill, Map.of("issue.late_fee_status", "17800"))
                .extractTemplateVariables(issues, matching(issues), SyntheticS73Notice.input(), SyntheticS73Notice.text(),
                        UUID.randomUUID(), null, notes, flags);

        MatchedIssue obj5 = merged.stream().filter(i -> i.issueNo() == 5).findFirst().orElseThrow();
        assertFalse(obj5.facts().containsKey("late_fee_status"), obj5.facts().toString());
        assertTrue(notes.stream().anyMatch(n -> n.contains("variable rejected: issue.late_fee_status expected text")), notes.toString());
        assertTrue(notes.stream().noneMatch(n -> n.contains("read from the document")), notes.toString());
        assertTrue(flags.contains(V7DraftingPipelineService.FLAG_VARIABLE_REJECTED));

        FilledTemplateResult ft = fill.fillTemplateForIssue(obj5, SyntheticS73Notice.notice(SyntheticS73Notice.TAX_TOTAL), SyntheticS73Notice.input());
        String html = ft.blocks().get(0).filledHtml();
        assertTrue(html.contains("which the noticee [[MISSING: late_fee_status]]"), html);
        assertFalse(html.contains("17800"), html);

        // in the reply: the short human placeholder, and an item to fill in the review sheet
        String all = ReplyLayoutText.render(layoutWith(merged, obj5, ft));
        assertTrue(all.contains("which the noticee [late fee status]"), all);
        assertFalse(all.contains("noticee 17800"), all);
        String review = all.substring(all.indexOf("A. ITEMS TO FILL BEFORE FILING"));
        assertTrue(review.substring(0, review.indexOf("B. POINTS FOR THE PARTNER")).contains("late fee status"), review);
    }

    @Test
    void goodTextValue_isFilled() {
        TemplateFillService fill = fill();
        List<MatchedIssue> issues = SyntheticS73Notice.issues();
        List<String> notes = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        List<MatchedIssue> merged = pipeline(fill, Map.of("issue.late_fee_status", "has been paid"))
                .extractTemplateVariables(issues, matching(issues), SyntheticS73Notice.input(), SyntheticS73Notice.text(),
                        UUID.randomUUID(), null, notes, flags);
        MatchedIssue obj5 = merged.stream().filter(i -> i.issueNo() == 5).findFirst().orElseThrow();
        assertEquals("has been paid", obj5.facts().get("late_fee_status"));
        assertFalse(flags.contains(V7DraftingPipelineService.FLAG_VARIABLE_REJECTED));
        String html = fill.fillTemplateForIssue(obj5, SyntheticS73Notice.notice(SyntheticS73Notice.TAX_TOTAL), SyntheticS73Notice.input())
                .blocks().get(0).filledHtml();
        assertTrue(html.contains("which the noticee has been paid —"), html);
    }

    @Test
    void fillGuard_alsoRejectsAWrongKindValueFromTheMatcherFacts() {
        TemplateFillService fill = fill();
        MatchedIssue base = SyntheticS73Notice.issues().get(4);
        Map<String, Object> facts = new LinkedHashMap<>(base.facts());
        facts.put("late_fee_status", "17800");
        FilledTemplateResult ft = fill.fillTemplateForIssue(base.withFacts(facts), SyntheticS73Notice.notice(SyntheticS73Notice.TAX_TOTAL),
                SyntheticS73Notice.input());
        assertTrue(ft.blocks().get(0).filledHtml().contains("[[MISSING: late_fee_status]]"), ft.blocks().get(0).filledHtml());
        assertTrue(ft.missingVars().contains("issue.late_fee_status"), ft.missingVars().toString());
    }

    // ------------------------------------------------------------------ helpers

    private TemplateFillService fill() {
        TemplateFillService fill = new TemplateFillService(null, null, om);
        fill.registerInMemoryTemplate(new ReplyTemplate("TPL-LF", 1, "CARD-5", LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31),
                "active", List.of(new TemplateBlock("LF-04", 4, BLOCK, List.of(), null)), List.of("issue.late_fee_status"),
                List.of(), "Late fee not payable.", List.of(), null, null, null));
        return fill;
    }

    private V7DraftingPipelineService pipeline(TemplateFillService fill, Map<String, String> values) {
        TemplateVariableAgent agent = mock(TemplateVariableAgent.class);
        when(agent.extractVariables(anyString(), anyList(), any(), any()))
                .thenReturn(new TemplateVariablesResult(Map.of(5, values), List.of(), "test-model", TemplateVariableAgent.PROMPT_VERSION));
        return new V7DraftingPipelineService(null, null, om, fill, new ReplyAssemblyService(null, null, om), new DraftCheckService(null),
                null, null, null, null, agent);
    }

    private static MatchingResult matching(List<MatchedIssue> issues) {
        return SyntheticS73Notice.matching(issues, SyntheticS73Notice.TAX_TOTAL);
    }

    private static ReplyLayout layoutWith(List<MatchedIssue> issues, MatchedIssue obj5, FilledTemplateResult ft) {
        List<MatchedIssue> withFull = new ArrayList<>();
        for (MatchedIssue i : issues) {
            withFull.add(i.issueNo() == 5 ? new MatchedIssue(5, "full", "demand", obj5.cardIds(), List.of(), "synthetic", List.of(),
                    obj5.facts(), obj5.noticeParas(), List.of(), List.of(), null) : i);
        }
        MatchingResult mr = SyntheticS73Notice.matching(withFull, SyntheticS73Notice.TAX_TOTAL);
        var ai = SyntheticS73Notice.aiFor(withFull);
        ai.remove(5);
        var r = SyntheticS73Notice.assemble(mr, ai, List.of(), Map.of(5, ft));
        return SyntheticS73Notice.layout(r);
    }
}
