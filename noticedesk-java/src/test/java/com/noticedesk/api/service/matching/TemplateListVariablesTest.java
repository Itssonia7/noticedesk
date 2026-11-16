package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import com.noticedesk.api.testsupport.SyntheticS74Notice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (b41) The variable issue.scrip_type (MEIS, read from the synthetic Section 74 notice) was filled in the issue body
 * but the documents-enclosed list, the annexure index and the client document list printed {{issue.scrip_type}}, and
 * CHECK_NO_UNRESOLVED_PLACEHOLDERS failed. Synthetic template (no partner content).
 */
class TemplateListVariablesTest {

    private static final String CARD = "SYN-RULE42";

    private final ObjectMapper om = new ObjectMapper();

    private TemplateFillService filler() {
        TemplateFillService f = new TemplateFillService(null, null, om);
        f.registerInMemoryTemplate(new ReplyTemplate("TPL-SYN-RULE42", 1, CARD, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31),
                "draft",
                List.of(new TemplateBlock("BLK-1", 4, "<p>The {{issue.scrip_type}} scrips were sold for {{issue.amount_tax}}.</p>", List.of(), null),
                        new TemplateBlock("BLK-2", 6, "<p>Rule 42 does not apply to the sale of {{issue.scrip_type}} scrips.</p>", List.of(), null)),
                List.of("issue.scrip_type", "issue.amount_tax"),
                List.of("Copies of the {{issue.scrip_type}} scrips and the sale invoices", "Ledger of {{issue.scrip_buyer}}"),
                "Sale of {{issue.scrip_type}} scrips", List.of("Object that the {{issue.scrip_type}} sale is not a supply"),
                null, null, null));
        return f;
    }

    private MatchedIssue issue2() {
        MatchedIssue base = SyntheticS74Notice.issues().get(1);
        return new MatchedIssue(2, "full", "demand", List.of(CARD), List.of(), "synthetic", List.of(), base.facts(), base.noticeParas(),
                List.of(), List.of());
    }

    @Test
    void documentsAndOptions_takeTheSameVariablesAsTheBody() {
        FilledTemplateResult ft = filler().fillTemplateForIssue(issue2(), SyntheticS74Notice.notice(SyntheticS74Notice.TOTAL),
                SyntheticS74Notice.input());

        assertTrue(ft.blocks().get(0).filledHtml().contains("The MEIS scrips"), ft.blocks().get(0).filledHtml());
        assertEquals("Copies of the MEIS scrips and the sale invoices", ft.documents().get(0));
        assertEquals("Object that the MEIS sale is not a supply", ft.proceduralObjectionOptions().get(0));
        // a list variable with no value prints the body's marker, not the raw variable
        assertEquals("Ledger of [[MISSING: scrip_buyer]]", ft.documents().get(1));
        assertTrue(ft.missingVars().contains("issue.scrip_buyer"), ft.missingVars().toString());
    }

    @Test
    void enclosedList_annexureIndex_andClientList_haveNoRawVariables_andThePlaceholderCheckPasses() {
        MatchedIssue issue = issue2();
        FilledTemplateResult ft = filler().fillTemplateForIssue(issue, SyntheticS74Notice.notice(SyntheticS74Notice.TOTAL),
                SyntheticS74Notice.input());
        MatchingResult mr = new MatchingResult(SyntheticS74Notice.notice(SyntheticS74Notice.TOTAL), List.of(issue), Map.of(), List.of(),
                List.of(), "hash", 1, 1, 0, 0);

        ReplyAssemblyService svc = new ReplyAssemblyService(null, null, om);
        svc.registerInMemoryStageTemplate(new StageTemplate("scn_74", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                new TreeMap<>(Map.of("08", "<p>Cross-examination.</p>", "12", "<p>PRAYER.</p>", "14", "<p>CLIENT SUMMARY.</p>",
                        "15", "<p>CHECKLIST.</p>")), null, null, null));
        AssembledReplyResult r = svc.assembleReply(mr, List.of(ft), SyntheticS74Notice.input(), "scn_74");

        for (int s : new int[] {10, 11, 14}) {
            String body = r.sections().stream().filter(x -> x.num() == s).map(AssembledSection::bodyHtml).findFirst().orElse("");
            assertTrue(body.contains("Copies of the MEIS scrips"), "section " + s + ": " + body);
            assertFalse(body.contains("{{"), "section " + s + ": " + body);
        }
        DraftCheckResult c = new DraftCheckService(null).runAllChecks(mr, r, SyntheticS74Notice.SECTION, "scn_74").stream()
                .filter(x -> "CHECK_NO_UNRESOLVED_PLACEHOLDERS".equals(x.checkName())).findFirst().orElseThrow();
        assertEquals(List.of(), c.details().get("unresolved_placeholders"), c.details().toString());
    }
}
