package com.noticedesk.api.model.rag;

import java.util.ArrayList;
import java.util.List;

public record RagContextBundle(
        List<EvidenceChunk> evidenceChunks,
        List<LegalChunk>    legalChunks,
        int                 totalTokens
) {
    public RagContextBundle {
        if (evidenceChunks == null) evidenceChunks = List.of();
        if (legalChunks == null) legalChunks = List.of();
    }

    public String formatForPrompt() {
        List<String> sections = new ArrayList<>();

        if (!legalChunks.isEmpty()) {
            sections.add("### RELEVANT LEGAL AUTHORITIES & CIRCULARS");
            for (LegalChunk chunk : legalChunks) {
                String header = "[" + chunk.actOrCircular() + " - " + chunk.sectionOrPara() + "]";
                if (chunk.title() != null && !chunk.title().isBlank()) {
                    header += " " + chunk.title();
                }
                sections.add(header + "\n" + chunk.content() + "\n");
            }
        }

        if (!evidenceChunks.isEmpty()) {
            sections.add("### RELEVANT CLIENT EVIDENCE & LEDGERS");
            for (EvidenceChunk chunk : evidenceChunks) {
                String source = chunk.filename() != null ? chunk.filename() :
                        (chunk.documentType() != null ? chunk.documentType() : "Evidence File");
                sections.add("[" + source + " - Chunk " + (chunk.chunkIndex() + 1) + "]\n" + chunk.content() + "\n");
            }
        }

        return String.join("\n", sections);
    }
}
