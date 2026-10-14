package com.noticedesk.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "noticedesk")
public class AppProperties {

    private String environment = "development";
    private String logLevel = "INFO";
    /** Directory holding private (non-committed) seed data; empty = private seeding skipped. */
    private String privateSeedDir = "";

    private Auth auth = new Auth();
    private Llm llm = new Llm();
    private Ocr ocr = new Ocr();
    private Storage storage = new Storage();
    private Email email = new Email();
    private Workflow workflow = new Workflow();
    private DocumentParsing documentParsing = new DocumentParsing();
    private Drafting drafting = new Drafting();
    private Embedding embedding = new Embedding();
    private String sentryDsn;
    private List<String> allowedOrigins = List.of("http://localhost:3000");
    private Matching matching = new Matching();
    private Pricing pricing = new Pricing();
    private Citation citation = new Citation();
    private Catalogue catalogue = new Catalogue();

    public boolean isDevAuthAllowed() {
        return "development".equals(environment) && "dev".equals(auth.getProvider());
    }

    @Data
    public static class Auth {
        private String provider = "dev";
        private String clerkJwksUrl;
        private String clerkIssuer;
    }

    @Data
    public static class Llm {
        private String providerPrimary = "stub";
        private String providerSecondary;
        private Anthropic anthropic = new Anthropic();
        private OpenAi openai = new OpenAi();
        private Gemini gemini = new Gemini();
        // Per-agent model overrides
        private String modelExtraction = "";
        private String modelFormatting = "";
        private String modelOpus = "";
        private String modelDrafting = "";
        private String modelTriage = "";
        private String modelParsing = "";
        private String modelMatching = "";
        private String modelPartialDrafting = "";
        private String modelNewIssue = "";
        private String modelHighStakes = "";
        private String stubDefaultCanned;

        // Per-agent thinking effort (output_config.effort) for models listed in anthropic.effort-models.
        // Blank = no effort sent (the model's own default applies). Values come from application.yml.
        private String effortMatching = "";
        private String effortPartialDrafting = "";
        private String effortNewIssue = "";
        private String effortHighStakes = "";

        // Per-agent max_tokens. A thinking model spends output tokens on thinking before the JSON,
        // so the cap must leave room for both. A max_tokens stop is retried once with the *Retry cap.
        // Integer so an empty env var binds to null and falls back to the default below.
        private Integer maxTokensMatching = 12000;
        private Integer maxTokensMatchingRetry = 20000;
        private Integer maxTokensPartialDrafting = 16000;
        private Integer maxTokensPartialDraftingRetry = 24000;
        private Integer maxTokensNewIssue = 16000;
        private Integer maxTokensNewIssueRetry = 24000;
        private Integer maxTokensHighStakes = 16000;
        private Integer maxTokensHighStakesRetry = 24000;

        /** Per-notice spend cap (INR) across all v7 model calls; once reached, remaining AI issues are left to the partner. */
        private Double maxCostInrPerNotice = 60.0;
        /** Per-notice limits for the high-stakes model. */
        private HighStakesLimits highStakes = new HighStakesLimits();
        /**
         * Logs a truncated sample (400 chars) of an unparseable writer response. Off by default: the
         * response can quote the notice. Enable only for the synthetic test bench.
         */
        private boolean debugLogRawOutput = false;

        @Data
        public static class HighStakesLimits {
            /** Maximum high-stakes model calls (retries included) for one notice. */
            private Integer maxCallsPerNotice = 6;
        }

        /** Effort for a v7 agent key (matching, partial_drafting, new_issue, high_stakes); "" when unset. */
        public String effortForAgent(String agent) {
            String v = switch (agent) {
                case "matching" -> effortMatching;
                case "partial_drafting" -> effortPartialDrafting;
                case "new_issue" -> effortNewIssue;
                case "high_stakes" -> effortHighStakes;
                default -> "";
            };
            return v != null ? v.trim() : "";
        }

        /** max_tokens for a v7 agent key; {@code retry} = the higher cap used after a max_tokens stop. */
        public int maxTokensForAgent(String agent, boolean retry) {
            return switch (agent) {
                case "matching" -> orDefault(retry ? maxTokensMatchingRetry : maxTokensMatching, retry ? 20000 : 12000);
                case "partial_drafting" -> orDefault(retry ? maxTokensPartialDraftingRetry : maxTokensPartialDrafting, retry ? 24000 : 16000);
                case "new_issue" -> orDefault(retry ? maxTokensNewIssueRetry : maxTokensNewIssue, retry ? 24000 : 16000);
                case "high_stakes" -> orDefault(retry ? maxTokensHighStakesRetry : maxTokensHighStakes, retry ? 24000 : 16000);
                default -> retry ? 16000 : 8000;
            };
        }

        private static int orDefault(Integer value, int fallback) {
            return value != null && value > 0 ? value : fallback;
        }

        @Data
        public static class Anthropic {
            private String apiKey;
            private String workspaceId;
            private String model = "";
            private double timeoutSeconds = 600.0;
            /**
             * Models that always think: they get output_config.effort and never thinking/temperature/
             * top_p/top_k. Other Anthropic models keep the legacy request shape.
             */
            private List<String> effortModels = new java.util.ArrayList<>();
            /** Upper bound applied to any max_tokens value sent to Anthropic. */
            private Integer maxTokensCeiling = 32000;
        }

        @Data
        public static class OpenAi {
            private String apiKey;
            private String model = "";
            private double timeoutSeconds = 180.0;
            private String baseUrl = "";
        }

        @Data
        public static class Gemini {
            private String apiKey;
            private String model = "";
            private double timeoutSeconds = 180.0;
        }
    }

    @Data
    public static class Matching {
        private boolean allowDraftCards = false;
    }

    @Data
    public static class Catalogue {
        /**
         * Whether matching sees provisional content: 'draft' issue cards promoted from an approved AI issue
         * section (and their 'provisional' reply templates). Default true in development so a promoted
         * template is reused on the next similar notice; set false to match against CA-approved cards only.
         */
        private boolean includeProvisional = true;
    }

    @Data
    public static class Pricing {
        private double inrPerUsd = 88.0;
        private java.util.Map<String, ModelPrice> models = new java.util.HashMap<>();

        @Data
        public static class ModelPrice {
            private double inputPerMillion = 0.0;
            private double outputPerMillion = 0.0;
            private double cacheReadPerMillion = 0.0;
            private double cacheWritePerMillion = 0.0;
        }
    }

    @Data
    public static class Ocr {
        private String providerPrimary = "stub";
        private String providerFallback;
        private GoogleDocAi googleDocAi = new GoogleDocAi();
        private AzureDocIntel azureDocIntel = new AzureDocIntel();

        @Data
        public static class GoogleDocAi {
            private String projectId;
            private String location;
            private String processorId;
        }

        @Data
        public static class AzureDocIntel {
            private String endpoint;
            private String apiKey;
        }
    }

    @Data
    public static class Storage {
        private String backend = "local";
        private String awsRegion = "ap-south-1";
        private String s3Bucket;
        private String localDir = "/tmp/noticedesk-storage";
        private long maxUploadBytes = 50L * 1024 * 1024;
    }

    @Data
    public static class Email {
        private String provider = "stub";
        private String fromAddress = "noreply@noticedesk.in";
        private String fromName = "NoticeDesk";
        private String stubDir = "/tmp/noticedesk-emails";
        private String inboundWebhookSecret;
        private String inboundDomain = "noticedesk.in";
        private Smtp smtp = new Smtp();

        @Data
        public static class Smtp {
            private String host;
            private int port = 587;
            private String username;
            private String password;
            private boolean useTls = true;
        }
    }

    @Data
    public static class Workflow {
        private String backend = "inline";
        private String temporalHost = "localhost:7233";
        private String temporalNamespace = "default";
        private String temporalTaskQueue = "noticedesk-ocr";
    }

    @Data
    public static class DocumentParsing {
        private String promptVersion = "v1";
        private int maxTokens = 4096;
        private double temperature = 0.0;
        private String citationProvider = "stub";
        private String indiankanoonApiToken;
    }

    @Data
    public static class Drafting {
        private String pipeline = "rag";
        private int ocrExcerptChars = 20000;
        private int maxOutputTokens = 16000;
        private int supportingDocExcerptChars = 5000;
        private int supportingEvidenceMaxChars = 40000;
        private double similarityThreshold = 0.70;
        private String demoFirmNameOverride;
        private String gstCorpusDir = "../NoticeDesk_GST_Corpus_1300_Paired/NoticeDesk_GST_Corpus_1-300_Paired";
        private int chunkTtlDays = 365;
        private String expiredChunkPolicy = "FLAG_AND_USE"; // FLAG_AND_USE | EXCLUDE
        private String citationFailPolicy = "FLAG_AND_CONTINUE"; // FLAG_AND_CONTINUE | FALL_THROUGH_TO_OPUS
        private HighStakes highStakes = new HighStakes();

        /** All three triggers off by default; set HIGH_STAKES_SECTION_74 / HIGH_STAKES_APPEAL_STAGE / HIGH_STAKES_DEMAND_THRESHOLD to enable. */
        @Data
        public static class HighStakes {
            private Boolean section74 = false;
            private Boolean appealStage = false;
            private String demandThreshold;  // empty/null = demand threshold check disabled
        }
    }

    @Data
    public static class Citation {
        private String provider = "stub";
        private String indiankanoonApiToken;
        private int maxCallsPerDraft = 15;
        private int cacheTtlDays = 30;
        private IndianKanoon indiankanoon = new IndianKanoon();

        /** Real per-call prices of the IndianKanoon API; 0 until set from the firm's IndianKanoon plan. */
        @Data
        public static class IndianKanoon {
            private double costInrPerSearch = 0.0;
            private double costInrPerFragment = 0.0;
        }
    }

    @Data
    public static class Embedding {
        private String provider = "stub";
        private String model = "";
        private double similarityThreshold = 0.70;
        private int outputDimensionality = 1536;
    }

    public String modelForAgent(String agent, String provider) {
        if ("openai".equals(provider)) {
            return llm.openai.model;
        } else if ("gemini".equals(provider)) {
            return llm.gemini.model;
        } else if (!"anthropic".equals(provider)) {
            return "";
        }
        String override = switch (agent) {
            case "extraction" -> llm.modelExtraction;
            case "formatting" -> llm.modelFormatting;
            case "opus", "template_generation" -> llm.modelOpus;
            case "drafting"   -> llm.modelDrafting;
            case "triage"     -> llm.modelTriage;
            case "parsing"    -> llm.modelParsing;
            case "matching"   -> llm.modelMatching;
            case "partial_drafting" -> llm.modelPartialDrafting;
            case "new_issue"        -> llm.modelNewIssue;
            case "high_stakes"      -> llm.modelHighStakes;
            default         -> "";
        };
        return (override != null && !override.isBlank()) ? override : llm.anthropic.model;
    }
}
