-- seed_reply_and_stage_templates_dev.sql
-- Development seeds for v7 Stage 2 reply and stage templates
-- NOTE: All dev blocks start with "[DEV - not CA reviewed]". Real production templates must be firm-approved.

-- 1. Reply Templates
INSERT INTO reply_templates (
    template_id, version, card_id, effective_from, effective_to, status, summary_line, variables, blocks, documents, procedural_objection_options
) VALUES
(
    'TPL-006', 1, 'CARD-006', '2017-07-01', '2099-12-31', 'draft',
    'Input Tax Credit disallowance under Section 16(2)(aa) based on GSTR-2B static statement discrepancy of Rs {{issue.amount_tax}}.',
    '["issue.amount_tax", "notice.din", "client.legal_name", "issue.reason_for_difference"]'::jsonb,
    '[
        {"id":"BLK-006-04A", "section":4, "html":"<p>[DEV - not CA reviewed] It is submitted that the discrepancy of Rs {{issue.amount_tax}} in GSTR-2B was due to {{issue.reason_for_difference}}, and all conditions of Section 16(2) were duly satisfied by {{client.legal_name}}.</p>", "citations":["CIT-001"]},
        {"id":"BLK-006-06A", "section":6, "html":"<p>[DEV - not CA reviewed] Section 16(2)(aa) cannot be applied retroactively or punitively where genuine tax has been deposited by the supplier into the public exchequer.</p>", "citations":["CIT-001"]}
    ]'::jsonb,
    '[]'::jsonb,
    '["GSTR-2B reconciliation report", "Supplier confirmation certificates"]'::jsonb
),
(
    'TPL-011', 1, 'CARD-011', '2017-07-01', '2099-12-31', 'draft',
    'Interest demand under Section 50 of Rs {{issue.amount_tax}} on gross tax liability.',
    '["issue.amount_tax", "notice.din", "client.legal_name"]'::jsonb,
    '[
        {"id":"BLK-011-04A", "section":4, "html":"<p>[DEV - not CA reviewed] The interest demand of Rs {{issue.amount_tax}} under Section 50 has been computed on gross tax liability instead of net cash liability. As per the proviso to Section 50(1), interest is payable only on the portion of tax paid by debiting the electronic cash ledger.</p>", "citations":["CIT-002"]},
        {"id":"BLK-011-06A", "section":6, "html":"<p>[DEV - not CA reviewed] Proviso to Section 50(1) inserted retrospectively from 01-07-2017 mandates calculation of interest solely on net cash tax liability.</p>", "citations":["CIT-002"]}
    ]'::jsonb,
    '[]'::jsonb,
    '["Electronic Cash Ledger statement", "Net tax liability computation sheet"]'::jsonb
),
(
    'TPL-004', 1, 'CARD-004', '2017-07-01', '2099-12-31', 'draft',
    'Input Tax Credit disallowance under Section 16(4) of Rs {{issue.amount_tax}}.',
    '["issue.amount_tax", "notice.din", "client.legal_name"]'::jsonb,
    '[
        {"id":"BLK-004-04A", "section":4, "html":"<p>[DEV - not CA reviewed] Regarding the disallowance of ITC amounting to Rs {{issue.amount_tax}} under Section 16(4), the statutory relaxation provided under Section 16(5) extends the deadline for FY 2018-19 to 30th November 2021.</p>", "citations":["CIT-003"]},
        {"id":"BLK-004-06A", "section":6, "html":"<p>[DEV - not CA reviewed] Retrospective statutory insertion of Section 16(5) overrides Section 16(4) time-bar for FY 2017-18 to 2020-21.</p>", "citations":["CIT-003"]}
    ]'::jsonb,
    '[]'::jsonb,
    '["GSTR-3B return filing acknowledgment", "Section 16(5) statutory notification copy"]'::jsonb
)
ON CONFLICT (template_id, version) DO UPDATE SET
    card_id = EXCLUDED.card_id,
    summary_line = EXCLUDED.summary_line,
    variables = EXCLUDED.variables,
    blocks = EXCLUDED.blocks,
    procedural_objection_options = EXCLUDED.procedural_objection_options;

-- 2. Stage Templates
INSERT INTO stage_templates (
    stage, version, effective_from, effective_to, status, sections
) VALUES
(
    'asmt_10', 1, '2017-07-01', '2099-12-31', 'draft',
    '{
        "08": "<p>[DEV - not CA reviewed] Request for cross-examination of third-party portal data relied upon during scrutiny.</p>",
        "12": "<p>[DEV - not CA reviewed] PRAYER: In view of the above facts and reconciliations, it is humbly prayed that the scrutiny proceedings under Form GST ASMT-10 be dropped in full. It is further requested that an opportunity of personal hearing be granted before passing any adverse order as mandated under Section 75(4) of the CGST Act, 2017.</p>",
        "14": "<p>[DEV - not CA reviewed] CLIENT SUMMARY: Intimation of discrepancy in return under Form GST ASMT-10. Reply due within 30 days.</p>",
        "15": "<p>[DEV - not CA reviewed] FILING CHECKLIST: 1. Form GST ASMT-11 reply 2. Reconciliation sheets 3. Authorisation Letter.</p>"
    }'::jsonb
),
(
    'scn_73', 1, '2017-07-01', '2099-12-31', 'draft',
    '{
        "08": "<p>[DEV - not CA reviewed] Request for cross-examination of departmental audit officers or third parties whose material is relied upon.</p>",
        "12": "<p>[DEV - not CA reviewed] PRAYER: It is humbly prayed that the Show Cause Notice issued under Section 73 be dropped in full and no penalty or interest be levied. It is further requested that an opportunity of personal hearing be granted before passing any adverse order as mandated under Section 75(4) of the CGST Act, 2017.</p>",
        "14": "<p>[DEV - not CA reviewed] CLIENT SUMMARY: Show Cause Notice under Section 73 demanding tax, interest, and penalty. Reply required before due date.</p>",
        "15": "<p>[DEV - not CA reviewed] FILING CHECKLIST: 1. Written Submissions 2. Form GST DRC-06 3. Authorisation Letter 4. Document Annexures.</p>"
    }'::jsonb
)
ON CONFLICT (stage, version) DO UPDATE SET
    sections = EXCLUDED.sections;
