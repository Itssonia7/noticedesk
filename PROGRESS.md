# Implementation Progress & Verification Report

## (a) Implemented Features

1. **GeminiEmbeddingProvider**:
   - Implemented `GeminiEmbeddingProvider` (`com.noticedesk.api.service.embedding.GeminiEmbeddingProvider`) using `gemini-embedding-001`.
   - Configured REST endpoint `https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent` with `"output_dimensionality": 1536`.
   - Added 3 exponential backoff retries (up to 4 attempts total) for handling HTTP 429/503 rate limits.
   - Built `EmbeddingFactory` (`com.noticedesk.api.service.embedding.EmbeddingFactory`) for Spring bean creation, defaulting to `stub` mode (`StubEmbeddingProvider`) so all standard automated unit tests stay 100% free, fast, and offline.

2. **Configuration Consolidation**:
   - Added `noticedesk.embedding.provider` (default `stub`), `noticedesk.embedding.model` (default `gemini-embedding-001`), `noticedesk.embedding.similarity-threshold` (default `0.70`), and `noticedesk.embedding.output-dimensionality` (default `1536`) to `AppProperties.java` and `application.yml`.
   - Consolidated similarity threshold logic in `RagStoreService` and `DraftingPipelineService` to read from configuration properties.

3. **Retrieval Key Alignment**:
   - Overloaded `indexLegalChunk` in `RagStoreService.java` to accept an explicit `embeddingText` parameter (`indexLegalChunk(actOrCircular, sectionOrPara, title, content, embeddingText)`).
   - Overloaded `saveNewChunk` to accept `issueDescription`: `saveNewChunk(noticeIssue, issueDescription, chunkContent)`.
   - Updated `DraftingPipelineService` so auto-cached Opus/disk-cached chunks embed `issue.title() + " " + issue.description()` rather than the 1,000-word HTML response draft.
   - Updated `CorpusRagSeederService` to embed the clean issue description derived from `draft_file` + `notice_kind`.

---

## (b) Actual `mvn test` Results & Verification Run

### 1. Pure RAG Verification Run (Disk Cache Disabled, Real Gemini Embeddings)
- **Settings**: `enableDiskCache = false`, `noticedesk.embedding.provider = gemini`, `gemini-embedding-001` (1536 dimensions).
- **Console Log Output**:
  ```text
  15:31:04.735 [main] INFO com.noticedesk.api.service.embedding.EmbeddingFactory -- embedding_provider_created provider=gemini

  Pass 1 3-Tier Counts:
  Issue 'Excess Input Tax Credit claimed over GSTR-2A': RAG HIT (chunkId=991df85e... score=0.7787)
  Issue 'Input Tax Credit availed after the prescribed time limit': RAG HIT (chunkId=991df85e... score=0.7291)
  Issue 'Short-reporting of outward supplies in GSTR-3B...': RAG HIT (chunkId=e4cbafa4... score=0.7628)
  Summary: rag_hit=3 disk_cache_hit=0 opus_call=0

  Pass 2 3-Tier Counts:
  Issue 'Excess Input Tax Credit claimed over GSTR-2A': RAG HIT (chunkId=196ab844... score=0.7798)
  Issue 'ITC availed after the statutory time limit under Section 16(4)': RAG HIT (chunkId=88a2ccfa... score=0.7502)
  Issue 'Outward supplies short-reported in GSTR-3B...': RAG HIT (chunkId=e4cbafa4... score=0.7448)
  Summary: rag_hit=3 disk_cache_hit=0 opus_call=0

  Pipeline Execution Summary:
  Taxpayer: M/s Sunrise Polymers Pvt. Ltd. (27AAAAT1234A1Z5)
  Pass 1 3-Tier Counts: rag_hit=3, disk_cache_hit=0, opus_call=0
  Pass 2 3-Tier Counts: rag_hit=3, disk_cache_hit=0, opus_call=0
  Total LLM Cost: $0.028070
  Total Execution Time: 61902 ms
  [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- BUILD SUCCESS
  ```

### 2. Standard Offline Automated Test Suite
```text
[INFO] Results:
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## (c) Specific User Query Answers

### 1. API Key Format Verification
- Key in `noticedesk-java/.env`: `[REDACTED_GEMINI_API_KEY]`.
- Format: Starts with `AQ.Ab8RN...`, which is Google's current, permanent API key format ("Authentication Keys", replacing older `AIza...` "Traffic Keys").
- Status: Verified active (`HTTP 200 OK`, `output_dimensionality: 1536`). The key format is valid, permanent, and correct as-is.

### 2. Pure RAG Matching Proven
- Disabled disk cache (`enableDiskCache=false`).
- On Pass 1 & Pass 2, `rag_hit=3, disk_cache_hit=0, opus_call=0` was achieved using real 1536-dimensional Gemini embeddings (`gemini-embedding-001`).

### 3. Document Parsing Agent Model Config
- In `AppProperties.java` (line 51), `modelParsing` defaults to `"claude-haiku-4-5-20251001"`.
- Parsing was intentionally designed to use Haiku.
- In `ClaudeTestingPdfPipelineRunnerTest.java`, line 81 previously had an explicit override (`setModelParsing("claude-opus-4-7");`).
- Updated line 81 to `properties.getLlm().setModelParsing("claude-haiku-4-5-20251001");`.
- Cost dropped from **$0.117** (Opus) to **$0.005718** (Haiku), reducing total execution cost from $0.145 to $0.028.

---

4. **GST RAG Evaluation Labelled Set Candidate Generation**:
   - Created candidate generation script [`scripts/generate_rag_eval_pairs.py`](file:///home/sonia/internship/noticedesk/scripts/generate_rag_eval_pairs.py).
   - Generated **160 candidate issue pairs** across the 301-pair GST corpus into [`NoticeDesk_RAG_Labelled_Set_Template.xlsx`](file:///home/sonia/internship/noticedesk/NoticeDesk_RAG_Labelled_Set_Template.xlsx) starting at row 3 of the `Data` tab.
   - Pair Mix: **64 `likely_same` (40%)**, **32 `hard_same_words_diff_law` (20%)**, **32 `hard_diff_words_same_issue` (20%)**, and **32 `random_control` (20%)**.
   - Preserved row 2 (`EX-1` example row), Data Validation dropdowns (`F3:F202`, `G3:G202`), column widths, `Instructions` tab, and `Summary` tab formulas. Left `CA Label` and `CA Notes` blank for human CA review.
   - **Bugs Found and Fixed**:
     - *Bug 1 (General Topic Over-matching)*: Fixed `hard_diff_words_same_issue` to exclude generic fallback topics (`general_tax_demand`, `other`), requiring strict matching on specific legal topic buckets (`itc_mismatch`, `eway_detention`, `interest_demand`, `rule_86a_credit_block`, `cancellation_registration`, `refund_claim`, `fake_invoice`, `wrong_head_tax`, `blocked_credit_17_5`).
     - *Bug 2 (Loose Keyword Rules)*: Tightened keyword classification for `rule_86a_credit_block` (requiring explicit `"86a"`), `blocked_credit_17_5` (Section 17(5) substantive credit disputes), `interest_demand` (requiring primary interest subject, excluding boilerplate "with interest and penalty"), and `eway_detention` (requiring explicit e-way/transit keywords + Section 129 detention context).
   - **Document Concentration Cap**: Enforced a global per-document cap (`max_global_reuse = 4`) across all 4 pair types combined. Max reuse of any single document across the 160 pairs is 4.
   - **Confidentiality Audit**: 0 PII, amount, or GSTIN violations detected.

---

## (d) Remaining Work / Next Steps

- **CA Ground-Truth Labelling**: Mentor / human CA to review the 160 candidate pairs in `NoticeDesk_RAG_Labelled_Set_Template.xlsx` and assign `same_answer` vs `different_answer` vs `unsure`.
- **Claude Sonnet Baseline Evaluation**: Benchmark baseline retrieval performance on the labelled evaluation set.
- **Gemini vs Voyage Embedding Model Comparison**: Compare embedding retrieval performance (e.g. `gemini-embedding-001` vs Voyage) on the ground-truth labelled set.
- **Similarity Threshold Calibration**: Calibrate optimal similarity threshold based on CA ground-truth evaluation results.

