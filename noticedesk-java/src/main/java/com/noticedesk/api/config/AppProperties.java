package com.noticedesk.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "noticedesk")
public class AppProperties {

    private String environment = "development";
    private String logLevel = "INFO";

    private Auth auth = new Auth();
    private Llm llm = new Llm();
    private Ocr ocr = new Ocr();
    private Storage storage = new Storage();
    private Email email = new Email();
    private Workflow workflow = new Workflow();
    private DocumentParsing documentParsing = new DocumentParsing();
    private Drafting drafting = new Drafting();
    private String sentryDsn;
    private List<String> allowedOrigins = List.of("http://localhost:3000");

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
        private String modelDrafting = "";
        private String modelTriage = "claude-sonnet-4-6";
        private String modelParsing = "claude-sonnet-4-6";
        private String stubDefaultCanned;

        @Data
        public static class Anthropic {
            private String apiKey;
            private String model = "claude-opus-4-7";
            private double timeoutSeconds = 180.0;
        }

        @Data
        public static class OpenAi {
            private String apiKey;
            private String model = "gpt-4o";
            private double timeoutSeconds = 180.0;
            private String baseUrl = "";
        }

        @Data
        public static class Gemini {
            private String apiKey;
            private String model = "gemini-3.6-flash";
            private double timeoutSeconds = 180.0;
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
        private int ocrExcerptChars = 20000;
        private int maxOutputTokens = 16000;
        private int supportingDocExcerptChars = 5000;
        private int supportingEvidenceMaxChars = 40000;
        private String demoFirmNameOverride;
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
            case "drafting" -> llm.modelDrafting;
            case "triage"   -> llm.modelTriage;
            case "parsing"  -> llm.modelParsing;
            default         -> "";
        };
        return (override != null && !override.isBlank()) ? override : llm.anthropic.model;
    }
}
