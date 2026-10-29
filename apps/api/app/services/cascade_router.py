"""Confidence Assessment Service for RAG-Guided Opus Drafting.

Evaluates vector similarity scores of retrieved RAG chunks to assign a confidence match
tier (HIGH_MATCH >= 70%, MEDIUM_MATCH 40-70%, NOVEL_CASE < 40%) and injects gold-standard
legal templates for high-confidence matches while retaining Claude Opus 4.7 for all drafting.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from app.models.rag import RagContextBundle

MatchTier = Literal["HIGH_MATCH", "MEDIUM_MATCH", "NOVEL_CASE"]

GOLD_STANDARD_GSTR2A_TEMPLATE = (
    "GOLD-STANDARD TEMPLATE GUIDANCE (FY 2017-18 / 2018-19 ITC Mismatch Defense):\n"
    "1. Synopsis: State demand under Section 73 is misconceived as GSTR-2A was a dynamic view statement.\n"
    "2. Primary Ground: Section 16(2) conditions (invoice possession, receipt of goods, payment) fully satisfied.\n"
    "3. CBIC Circular 183/15/2022-GST: Mismatches are verification triggers, not automatic reversal grounds.\n"
    "4. Prospective Application: Rule 36(4) matching restrictions apply prospectively from 9 Oct 2019.\n"
    "5. Judicial Precedents: Cite Calcutta HC Suncraft Energy (2023) and Madras HC D.Y. Beathel (2021) "
    "confirming department must first proceed against defaulting supplier before reversing buyer ITC."
)


@dataclass(frozen=True, slots=True)
class ConfidenceAssessment:
    confidence_score: float
    is_high_confidence: bool
    match_tier: MatchTier
    guided_template: str | None
    drafting_model: str = "claude-opus-4-7"


def evaluate_cascade_confidence(rag_context: RagContextBundle | None) -> ConfidenceAssessment:
    """Evaluate RAG similarity score and determine template guidance while keeping Opus 4.7 as model."""
    if not rag_context:
        return ConfidenceAssessment(
            confidence_score=0.0,
            is_high_confidence=False,
            match_tier="NOVEL_CASE",
            guided_template=None,
            drafting_model="claude-opus-4-7",
        )

    all_scores: list[float] = []

    for chunk in rag_context.legal_chunks:
        if chunk.similarity_score is not None:
            all_scores.append(chunk.similarity_score)

    for chunk in rag_context.evidence_chunks:
        if chunk.similarity_score is not None:
            all_scores.append(chunk.similarity_score)

    max_score = max(all_scores) if all_scores else 0.0

    if max_score >= 0.70:
        return ConfidenceAssessment(
            confidence_score=round(max_score, 4),
            is_high_confidence=True,
            match_tier="HIGH_MATCH",
            guided_template=GOLD_STANDARD_GSTR2A_TEMPLATE,
            drafting_model="claude-opus-4-7",
        )

    if max_score >= 0.40:
        return ConfidenceAssessment(
            confidence_score=round(max_score, 4),
            is_high_confidence=False,
            match_tier="MEDIUM_MATCH",
            guided_template=None,
            drafting_model="claude-opus-4-7",
        )

    return ConfidenceAssessment(
        confidence_score=round(max_score, 4),
        is_high_confidence=False,
        match_tier="NOVEL_CASE",
        guided_template=None,
        drafting_model="claude-opus-4-7",
    )
