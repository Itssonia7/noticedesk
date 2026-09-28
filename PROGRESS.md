# Pipeline Refactoring Progress Log

## (a) Approved Spec & Technical Decisions
- **Architecture Flow**: Implemented Option A 5-step pipeline in `DraftingWorkflow.java` (after OCR + Triage):
  1. **Issue Extraction**: Claude Haiku reads notice & OCR excerpt, returning structured JSON (`ExtractionResult` with `ExtractedIssue` list and `ExtractedNoticeInfo`).
  2. **Per-Issue RAG Search & Deduplication**: Vector search per issue against RAG store (`similarityThreshold = 0.70`). Matched chunks are stored in a `LinkedHashSet` to deduplicate identical chunks automatically.
  3. **Unmatched Issues Template Generation**: Sends **ONLY** unmatched issues to Claude Opus to generate a statutory rebuttal template chunk for each. Saves newly generated template chunks to `RagStoreService` (`saveNewChunk(...)`).
  4. **Formatting**: Merges all deduplicated chunks (retrieved + newly generated) into a final **15-section JSON reply** via Claude Haiku.
  5. **Citation Verification**: Runs `CitationVerificationAgent.verify(...)` on the concatenated HTML of the final 15 sections.
- **Model Defaults**:
  - `LLM_MODEL_EXTRACTION` & `LLM_MODEL_FORMATTING`: `claude-haiku-4-5-20251001`
  - `LLM_MODEL_OPUS`: `claude-opus-4-7`
  - No `claude-3-*` models used.
- **Extraction Failures**:
  - `extractIssues` retries once if JSON parsing fails.
  - If the retry also fails, it logs `extract_issues_failed_after_retry` and throws `JsonSchemaValidationException`. No silent fallback to a single issue.
- **15-Section Draft Contract**:
  - Enforced exact 15-section JSON structure matching `GstTemplateFillerService` and DOCX export.
- **Anthropic Workspace ID**:
  - Workspace ID is loaded via `AppProperties` (`noticedesk.llm.anthropic.workspace-id` / `${ANTHROPIC_WORKSPACE_ID:}`) and passed directly to `AnthropicLlmProvider`. Hardcoded fallback `wrkspc_014N4cnyTQiXVFyARaUjgt45` removed across Java, Python, and benchmark script.

---

## (b) Steps Completed with File Names

1. **Configuration & Properties**:
   - `noticedesk-java/src/main/resources/application.yml`: Updated model overrides to `claude-haiku-4-5-20251001` and `claude-opus-4-7`. Added `anthropic.workspace-id`.
   - `noticedesk-java/src/main/java/com/noticedesk/api/config/AppProperties.java`: Added `modelExtraction`, `modelFormatting`, `modelOpus`, `similarityThreshold`, and `workspaceId` fields.
2. **LLM Provider & Factory Layer**:
   - `noticedesk-java/src/main/java/com/noticedesk/api/service/llm/AnthropicLlmProvider.java`: Removed hardcoded workspace ID fallback. Receives `workspaceId` parameter from `AppProperties`.
   - `noticedesk-java/src/main/java/com/noticedesk/api/service/llm/LlmFactory.java`: Updated `getOpusProvider()` to use `AppProperties.getModelOpus()` and pass `workspaceId`.
   - `scripts/benchmark_sonnet_vs_opus.py`: Removed hardcoded `workspace_id` string; reads from environment variable.
3. **Core Pipeline Implementation**:
   - `noticedesk-java/src/main/java/com/noticedesk/api/agent/DraftingAgent.java`: Implemented `extractIssues` (with 1-retry and exception throw), `generateOpusTemplateForUnmatchedIssue`, and `mergeAndFormatDraft` (15-section contract).
   - `noticedesk-java/src/main/java/com/noticedesk/api/workflow/DraftingWorkflow.java`: Integrated 5-step pipeline with per-issue RAG, `LinkedHashSet` chunk deduplication, Opus unmatched generation & caching, Haiku 15-section formatting, and `CitationVerificationAgent` Step 5.
4. **Unit & Integration Tests Written**:
   - `noticedesk-java/src/test/java/com/noticedesk/api/agent/DraftingAgentTest.java`: Added `testExtractionFailure_RetriesOnceAndThrowsException` and `testExtractionSuccess`.
   - `noticedesk-java/src/test/java/com/noticedesk/api/workflow/DraftingPipelineTest.java`: Created test suite for chunk deduplication (`testPipelineDeduplicationWhenTwoIssuesMatchSameChunk`) and multi-issue scenarios (2/3 matched + 1/3 unmatched, 15-section formatting).

---

## (c) Step in Progress & Remaining Work

- **Status**: Complete. All unit and integration pipeline tests (`DraftingAgentTest`, `DraftingPipelineTest`, `RagStoreServiceTest`, `GstCorpusMatcherServiceTest`, `GstTemplateFillerServiceTest`) ran and passed cleanly (`BUILD SUCCESS`).
- **Remaining Work**: None.

---

## (d) Verified Test Output

```
[INFO] Running com.noticedesk.api.agent.DraftingAgentTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in DraftingAgentTest
[INFO] Running com.noticedesk.api.workflow.DraftingPipelineTest
18:53:04.377 INFO  DraftingAgent -- extract_issues_success attempt=1 issuesCount=3
18:53:04.386 INFO  DraftingAgent -- Generating Opus template chunk for unmatched issue: Novel Crypto Tax
18:53:04.396 INFO  RagStoreService -- Auto-caching newly discovered issue chunk into RAG legal_chunks: Novel Crypto Tax
18:53:04.399 INFO  DraftingAgent -- Merging and formatting 3 unique chunks into 15-section final draft via Haiku.
18:53:04.457 INFO  DraftingAgent -- draft generated provider=null model=haiku sections=15 tokens_out=30
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in DraftingPipelineTest
[INFO] BUILD SUCCESS
```
