"""Sprint 3 end-to-end: OCR → parse → route, all stubbed.

Requires TEST_DATABASE_URL like the other integration tests. Drives the
parsing agent with a StubLLMProvider whose response factory pattern-matches
on the user prompt so we get deterministic routing decisions per scenario:

  - 'routed'                       — exact PAN match for seeded client
  - 'client_not_found'             — PAN doesn't match any client
  - 'new_gst_registration_detected' — PAN matches client, GSTIN is new
  - 'pan_gstin_mismatch'           — PAN and GSTIN disagree
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import uuid
from pathlib import Path

import pytest
from sqlalchemy import text

if not os.environ.get("TEST_DATABASE_URL"):
    pytest.skip("TEST_DATABASE_URL not set", allow_module_level=True)


SEEDED_PAN = "AAACA9876B"
SEEDED_GSTIN = "27AAACA9876B1Z5"   # Maharashtra GST registered for the client


@pytest.fixture(autouse=True)
def _env(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("DATABASE_URL", os.environ["TEST_DATABASE_URL"])
    monkeypatch.setenv("STORAGE_BACKEND", "local")
    monkeypatch.setenv("LOCAL_STORAGE_DIR", str(tmp_path))
    monkeypatch.setenv("OCR_PROVIDER_PRIMARY", "stub")
    monkeypatch.setenv("OCR_PROVIDER_FALLBACK", "")
    monkeypatch.setenv("LLM_PROVIDER_PRIMARY", "stub")
    monkeypatch.setenv("LLM_PROVIDER_SECONDARY", "")
    monkeypatch.setenv("WORKFLOW_BACKEND", "inline")
    monkeypatch.setenv("ENVIRONMENT", "development")
    monkeypatch.setenv("AUTH_PROVIDER", "dev")


def stub_response_for(ocr_text: str) -> str:
    """Build a parsed-JSON response keyed off the OCR text our test seeds."""
    if "MISMATCH" in ocr_text:
        return json.dumps({
            "document_type": "DRC-01",
            "law": "GST",
            "pans_extracted": [{"value": "ZZZZZ9999Z", "location": "page 1"}],
            "gstins_extracted": [{"value": SEEDED_GSTIN, "location": "page 1", "state_code": "27"}],
            "issues": [], "documents_required": [],
            "parse_confidence": 0.85,
            "fields_needing_review": [],
            "financial_year": "2022-23",
        })
    if "NEW-STATE-GSTIN" in ocr_text:
        return json.dumps({
            "document_type": "ASMT-10",
            "law": "GST",
            "pans_extracted": [],
            "gstins_extracted": [
                {"value": "29AAACA9876B1ZK", "location": "page 1", "state_code": "29"}
            ],
            "issues": [], "documents_required": [],
            "parse_confidence": 0.9,
            "fields_needing_review": [],
        })
    if "UNKNOWN-PAN" in ocr_text:
        return json.dumps({
            "document_type": "IT_142(1)",
            "law": "IT",
            "pans_extracted": [{"value": "ABCDE1234F", "location": "page 1"}],
            "gstins_extracted": [],
            "issues": [], "documents_required": [],
            "parse_confidence": 0.9,
            "fields_needing_review": [],
            "assessment_year": "2023-24",
        })
    # Default: clean route to the seeded client / GST registration.
    return json.dumps({
        "document_type": "ASMT-10",
        "law": "GST",
        "pans_extracted": [],
        "gstins_extracted": [{"value": SEEDED_GSTIN, "location": "page 1", "state_code": "27"}],
        "issues": ["ITC mismatch"],
        "documents_required": ["GSTR-3B for 2022-23"],
        "parse_confidence": 0.92,
        "fields_needing_review": [],
        "financial_year": "2022-23",
    })


@pytest.fixture
def patch_llm(monkeypatch):
    from app.services.llm.stub import StubLLMProvider
    import app.services.llm.factory as factory

    def factory_stub(system: str, user: str) -> str:
        m = re.search(r"---- OCR TEXT BEGINS ----\n(.*?)\n---- OCR TEXT ENDS ----", user, re.DOTALL)
        ocr = m.group(1) if m else ""
        return stub_response_for(ocr)

    provider = StubLLMProvider(response_factory=factory_stub)
    monkeypatch.setattr(factory, "get_llm_for_agent", lambda agent: provider)
    monkeypatch.setattr(factory, "get_secondary_llm_for_agent", lambda agent: None)
    # The parsing agent imports these directly; patch both points.
    import app.agents.document_parsing as dp
    monkeypatch.setattr(dp, "get_llm_for_agent", lambda agent: provider)
    monkeypatch.setattr(dp, "get_secondary_llm_for_agent", lambda agent: None)


async def _seed_tenant_and_client() -> tuple[uuid.UUID, uuid.UUID, uuid.UUID]:
    from app.core.db import session_local

    suffix = uuid.uuid4().hex[:8]
    async with session_local()() as session:
        tenant = (
            await session.execute(
                text("INSERT INTO tenants (legal_name) VALUES (:n) RETURNING tenant_id"),
                {"n": f"Routing Firm {suffix}"},
            )
        ).scalar_one()
        client = (
            await session.execute(
                text(
                    "INSERT INTO clients (tenant_id, pan, legal_name) "
                    "VALUES (:t, :pan, :name) RETURNING client_id"
                ),
                {"t": tenant, "pan": SEEDED_PAN, "name": "Acme Industries Pvt Ltd"},
            )
        ).scalar_one()
        await session.execute(
            text(
                "INSERT INTO client_registrations (tenant_id, client_id, registration_type, identifier_value) "
                "VALUES (:t, :c, 'IT', :pan)"
            ),
            {"t": tenant, "c": client, "pan": SEEDED_PAN},
        )
        await session.execute(
            text(
                "INSERT INTO client_registrations (tenant_id, client_id, registration_type, "
                "identifier_value, state_code, state_name) "
                "VALUES (:t, :c, 'GST', :gstin, '27', 'Maharashtra')"
            ),
            {"t": tenant, "c": client, "gstin": SEEDED_GSTIN},
        )
        await session.commit()
        return uuid.UUID(str(tenant)), uuid.UUID(str(client)), tenant


async def _seed_inbox_row(tenant_id: uuid.UUID, ocr_text: str) -> uuid.UUID:
    from app.core.db import session_for_tenant
    from app.services.storage import get_storage

    inbox_id = uuid.uuid4()
    payload = b"%PDF-1.4 stub" + ocr_text.encode() + b"\n"
    s3_key = f"tenants/{tenant_id}/inbox/{inbox_id}/n.pdf"
    await get_storage().put(s3_key, payload, content_type="application/pdf")

    async with session_for_tenant(tenant_id) as session:
        await session.execute(
            text(
                """
                INSERT INTO documents_inbox (
                    inbox_id, tenant_id, original_filename, file_hash,
                    file_size_bytes, mime_type, s3_key, ingest_channel,
                    ocr_status, ocr_text, ocr_provider_used, page_count,
                    ocr_completed_at
                ) VALUES (
                    :id, :t, 'n.pdf', :h, :sz, 'application/pdf', :k, 'web_upload',
                    'completed', :txt, 'stub', 1, NOW()
                )
                """
            ),
            {
                "id": str(inbox_id),
                "t": str(tenant_id),
                "h": hashlib.sha256(payload).hexdigest(),
                "sz": len(payload),
                "k": s3_key,
                "txt": ocr_text,
            },
        )
        await session.commit()
    return inbox_id


async def _read_inbox(tenant_id, inbox_id):
    from app.core.db import session_for_tenant
    async with session_for_tenant(tenant_id) as session:
        row = (
            await session.execute(
                text(
                    """
                    SELECT routing_status, parse_status, parsed_to_notice_id,
                           routing_anomaly_details, raw_parsed_json
                    FROM documents_inbox WHERE inbox_id = :id
                    """
                ),
                {"id": str(inbox_id)},
            )
        ).first()
        return row


# ---- Scenarios --------------------------------------------------------------


@pytest.mark.asyncio
async def test_routed_creates_notice(patch_llm):
    from app.workflows.document_parsing_and_routing import (
        ParseAndRouteJob,
        run_parse_and_route,
    )

    tenant_id, _, _ = await _seed_tenant_and_client()
    inbox = await _seed_inbox_row(tenant_id, "GST ASMT-10 routine notice for Acme")

    decision = await run_parse_and_route(
        ParseAndRouteJob(inbox_id=inbox, tenant_id=tenant_id)
    )
    assert decision is not None
    assert decision.routing_status == "routed"
    assert decision.canonical_pan == SEEDED_PAN
    assert decision.created_notice_id is not None

    row = await _read_inbox(tenant_id, inbox)
    assert row[0] == "routed"
    assert row[2] is not None  # parsed_to_notice_id


@pytest.mark.asyncio
async def test_client_not_found(patch_llm):
    from app.workflows.document_parsing_and_routing import (
        ParseAndRouteJob,
        run_parse_and_route,
    )

    tenant_id, _, _ = await _seed_tenant_and_client()
    inbox = await _seed_inbox_row(tenant_id, "IT notice for UNKNOWN-PAN taxpayer")

    decision = await run_parse_and_route(
        ParseAndRouteJob(inbox_id=inbox, tenant_id=tenant_id)
    )
    assert decision is not None
    assert decision.routing_status == "client_not_found"
    assert decision.canonical_pan == "ABCDE1234F"


@pytest.mark.asyncio
async def test_new_gst_registration_detected(patch_llm):
    from app.workflows.document_parsing_and_routing import (
        ParseAndRouteJob,
        run_parse_and_route,
    )

    tenant_id, client_id, _ = await _seed_tenant_and_client()
    inbox = await _seed_inbox_row(tenant_id, "GST notice for Acme NEW-STATE-GSTIN Karnataka")

    decision = await run_parse_and_route(
        ParseAndRouteJob(inbox_id=inbox, tenant_id=tenant_id)
    )
    assert decision is not None
    assert decision.routing_status == "new_gst_registration_detected"
    assert decision.canonical_pan == SEEDED_PAN
    details = decision.anomaly_details or {}
    assert details.get("gstin") == "29AAACA9876B1ZK"
    assert details.get("client_id") == str(client_id)


@pytest.mark.asyncio
async def test_pan_gstin_mismatch_blocks(patch_llm):
    from app.workflows.document_parsing_and_routing import (
        ParseAndRouteJob,
        run_parse_and_route,
    )

    tenant_id, _, _ = await _seed_tenant_and_client()
    inbox = await _seed_inbox_row(tenant_id, "GST DRC-01 MISMATCH test")

    decision = await run_parse_and_route(
        ParseAndRouteJob(inbox_id=inbox, tenant_id=tenant_id)
    )
    assert decision is not None
    assert decision.routing_status == "pan_gstin_mismatch"
    details = decision.anomaly_details or {}
    assert details.get("extracted_pan") == "ZZZZZ9999Z"
    assert details.get("gstin_pan_portion") == "AAACA9876B"

    row = await _read_inbox(tenant_id, inbox)
    assert row[0] == "pan_gstin_mismatch"
    assert row[2] is None  # no notice created
