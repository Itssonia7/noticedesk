// Browser-side API client. The Next.js dev session cookie is read on the
// server only, so client components proxy through Next.js route handlers
// (under app/api/*) rather than calling the FastAPI directly. Server
// components fetch the API directly.

import type {
  InboxList,
  OcrTextResponse,
  UploadResponse,
} from "@noticedesk/shared/inbox";

export type { InboxItem, InboxList, UploadResponse, OcrTextResponse } from "@noticedesk/shared/inbox";

export async function fetchInbox(): Promise<InboxList> {
  const res = await fetch("/api/inbox", { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load inbox (${res.status})`);
  const data = await res.json();
  if (Array.isArray(data)) {
    return { items: data, total: data.length };
  }
  return data as InboxList;
}

export async function uploadDocument(
  file: File,
  ingestChannel: "web_upload" | "mobile_capture",
  onProgress?: (pct: number) => void,
): Promise<UploadResponse> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `/api/upload?ingest_channel=${ingestChannel}`);
    xhr.responseType = "json";
    if (onProgress) {
      xhr.upload.addEventListener("progress", (e) => {
        if (e.lengthComputable) onProgress((e.loaded / e.total) * 100);
      });
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(xhr.response as UploadResponse);
      } else {
        reject(
          new Error(
            (xhr.response as { error?: { message?: string } })?.error?.message ??
              `upload failed (${xhr.status})`,
          ),
        );
      }
    };
    xhr.onerror = () => reject(new Error("network error during upload"));
    const fd = new FormData();
    fd.append("file", file);
    xhr.send(fd);
  });
}

export async function fetchOcrText(inboxId: string): Promise<OcrTextResponse> {
  const res = await fetch(`/api/ocr/${inboxId}`, { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load OCR text (${res.status})`);
  return (await res.json()) as OcrTextResponse;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let detail = "";
    try {
      const j = (await res.json()) as { error?: { message?: string } };
      detail = j?.error?.message ?? "";
    } catch {
      // ignore
    }
    throw new Error(detail || `request failed (${res.status})`);
  }
  return (await res.json()) as T;
}

export interface ManualRouteBody {
  client_id: string;
  registration_id: string;
  override_reason: string;
}

export function routeManually(
  inboxId: string,
  body: ManualRouteBody,
): Promise<{ inbox_id: string; routing_status: string }> {
  return postJson(`/api/route-manually/${inboxId}`, body);
}

export function rejectInbox(
  inboxId: string,
  reason: string,
): Promise<{ status: string }> {
  return postJson(`/api/reject/${inboxId}`, { reason });
}

export interface AddClientBody {
  pan: string;
  legal_name: string;
  trade_name?: string | null;
  entity_type?: string | null;
  industry?: string | null;
  auto_route_inbox_id?: string | null;
}

export function addClient(body: AddClientBody): Promise<{ client_id: string }> {
  return postJson("/api/clients", body);
}

export interface ClientSummary {
  client_id: string;
  pan: string;
  legal_name: string;
  trade_name: string | null;
  entity_type: string | null;
  industry: string | null;
  gst_count: number;
  gst_state_codes: string[];
  active_it_count: number;
  active_gst_count: number;
  earliest_open_due_date: string | null;
}

export interface ClientList {
  clients: ClientSummary[];
  total: number;
}

export async function fetchClients(): Promise<ClientList> {
  const res = await fetch("/api/clients", { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load clients (${res.status})`);
  const data = await res.json();
  if (Array.isArray(data)) {
    return { clients: data, total: data.length };
  }
  return data as ClientList;
}

export interface ClientRegistration {
  registration_id: string;
  registration_type: "IT" | "GST";
  identifier_value: string;
  state_code: string | null;
  state_name: string | null;
  jurisdiction_office: string | null;
  registration_status: string | null;
  active_notice_count: number;
  earliest_open_due_date: string | null;
  sync_method: "manual" | "portal";
  last_synced_at: string | null;
}

export interface ClientDetail {
  client_id: string;
  pan: string;
  legal_name: string;
  trade_name: string | null;
  entity_type: string | null;
  cin: string | null;
  date_of_incorporation_or_birth: string | null;
  industry: string | null;
  email: string | null;
  phone: string | null;
  registrations: ClientRegistration[];
}

export async function fetchClient(id: string): Promise<ClientDetail> {
  const res = await fetch(`/api/clients/${id}`, { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load client (${res.status})`);
  return (await res.json()) as ClientDetail;
}

export interface UpdateClientBody {
  legal_name?: string;
  trade_name?: string | null;
  entity_type?: string | null;
  industry?: string | null;
  cin?: string | null;
  email?: string | null;
  phone?: string | null;
}

export async function updateClient(
  id: string,
  body: UpdateClientBody,
): Promise<{ client_id: string; updated_fields: string[] }> {
  const res = await fetch(`/api/clients/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `update failed (${res.status})`);
  }
  return (await res.json()) as { client_id: string; updated_fields: string[] };
}

export async function deleteClient(id: string): Promise<{ deleted: true }> {
  const res = await fetch(`/api/clients/${id}`, { method: "DELETE" });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `delete failed (${res.status})`);
  }
  return (await res.json()) as { deleted: true };
}

export interface RegistrationNotice {
  notice_id: string;
  document_type: string | null;
  due_date: string | null;
  hearing_date: string | null;
  authority: string | null;
  financial_year: string | null;
  assessment_year: string | null;
  lifecycle_status: string;
  ingest_channel: string;
  din_or_rfn: string | null;
  issue: string | null;
  assigned_to: string | null;
}

export interface RegistrationNoticesResponse {
  registration: {
    registration_id: string;
    registration_type: "IT" | "GST";
    identifier_value: string;
    state_code: string | null;
    state_name: string | null;
    jurisdiction_office: string | null;
    registration_status: string | null;
    sync_method: "manual" | "portal";
    last_synced_at: string | null;
  };
  client: {
    client_id: string;
    legal_name: string;
    pan: string;
  };
  notices: RegistrationNotice[];
  total: number;
}

export async function fetchRegistrationNotices(
  registrationId: string,
): Promise<RegistrationNoticesResponse> {
  const res = await fetch(`/api/registrations/${registrationId}/notices`, {
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`failed to load notices (${res.status})`);
  return (await res.json()) as RegistrationNoticesResponse;
}

export interface UpdateRegistrationBody {
  jurisdiction_office?: string | null;
  state_name?: string | null;
  registration_status?: "active" | "suspended" | "cancelled" | "surrendered";
}

export async function updateRegistration(
  registrationId: string,
  body: UpdateRegistrationBody,
): Promise<{ registration_id: string; updated_fields: string[] }> {
  const res = await fetch(`/api/registrations/${registrationId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `update failed (${res.status})`);
  }
  return (await res.json()) as { registration_id: string; updated_fields: string[] };
}

export interface NoticeListRow {
  notice_id: string;
  law: "GST" | "IT";
  document_type: string | null;
  due_date: string | null;
  financial_year: string | null;
  assessment_year: string | null;
  lifecycle_status: string;
  ingest_channel: string;
  din_or_rfn: string | null;
  issue: string | null;
  assigned_to: string | null;
  client_id: string;
  client_legal_name: string;
  client_pan: string;
  registration_id: string;
  registration_type: "IT" | "GST";
  registration_identifier: string;
  registration_state_code: string | null;
  registration_state_name: string | null;
}

export interface NoticeListResponse {
  notices: NoticeListRow[];
  total: number;
  page: number;
  page_size: number;
}

export interface NoticeListFilters {
  status?: string;
  law?: "GST" | "IT";
  client_id?: string;
  registration_id?: string;
  state_code?: string;
  from?: string;
  to?: string;
  page?: number;
  page_size?: number;
}

export async function fetchNotices(
  filters: NoticeListFilters = {},
): Promise<NoticeListResponse> {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(filters)) {
    if (v === undefined || v === null || v === "") continue;
    qs.set(k, String(v));
  }
  const url = qs.toString() ? `/api/notices?${qs.toString()}` : "/api/notices";
  const res = await fetch(url, { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load notices (${res.status})`);
  return (await res.json()) as NoticeListResponse;
}

export type StatusCounts = Record<string, number>;

export async function fetchStatusCounts(): Promise<StatusCounts> {
  const res = await fetch("/api/dashboard/status_counts", { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load status counts (${res.status})`);
  return (await res.json()) as StatusCounts;
}

export interface NoticeDetail {
  notice: {
    notice_id: string;
    matter_id: string;
    law: "GST" | "IT";
    document_type: string | null;
    notice_number: string | null;
    din_or_rfn: string | null;
    issue_date: string | null;
    receipt_date: string | null;
    due_date: string | null;
    hearing_date: string | null;
    authority: string | null;
    financial_year: string | null;
    assessment_year: string | null;
    issues: unknown;
    documents_required: unknown;
    lifecycle_status: string;
    ingest_channel: string;
    demand_amount: number | null;
    source_inbox_id: string | null;
    pan_gstin_reconciliation_status: string | null;
    parse_confidence: number | null;
    verification_status: string | null;
    issue: string | null;
    assigned_to: string | null;
    raw_extracted_json: Record<string, unknown> | null;
    manual_corrections_json: Record<string, unknown> | null;
    created_at: string | null;
    updated_at: string | null;
  };
  client: {
    client_id: string;
    legal_name: string;
    pan: string;
    entity_type: string | null;
    industry: string | null;
  };
  registration: {
    registration_id: string;
    registration_type: "IT" | "GST";
    identifier_value: string;
    state_code: string | null;
    state_name: string | null;
    jurisdiction_office: string | null;
  };
}

export async function fetchNotice(id: string): Promise<NoticeDetail> {
  const res = await fetch(`/api/notices/${id}`, { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load notice (${res.status})`);
  return (await res.json()) as NoticeDetail;
}

export interface UpdateNoticeBody {
  document_type?: string;
  notice_number?: string;
  din_or_rfn?: string;
  due_date?: string;
  issue_date?: string;
  hearing_date?: string;
  authority?: string;
  demand_amount?: number;
}

export async function updateNotice(
  id: string,
  body: UpdateNoticeBody,
): Promise<{ notice_id: string; updated_fields: string[] }> {
  const res = await fetch(`/api/notices/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `update failed (${res.status})`);
  }
  return (await res.json()) as { notice_id: string; updated_fields: string[] };
}

export interface TransitionLifecycleBody {
  lifecycle_status: string;
  reason?: string;
}

export async function transitionLifecycle(
  id: string,
  body: TransitionLifecycleBody,
): Promise<{ notice_id: string; lifecycle_status: string; changed: boolean }> {
  const res = await fetch(`/api/notices/${id}/lifecycle`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `transition failed (${res.status})`);
  }
  return (await res.json()) as {
    notice_id: string;
    lifecycle_status: string;
    changed: boolean;
  };
}

export interface TimelineEvent {
  audit_id: string;
  timestamp: string;
  action_type: string;
  before_state: Record<string, unknown> | null;
  after_state: Record<string, unknown> | null;
  risk_tier: number | null;
  user_name: string | null;
  user_role: string | null;
}

export async function fetchTimeline(
  id: string,
): Promise<{ events: TimelineEvent[]; total: number }> {
  const res = await fetch(`/api/notices/${id}/timeline`, { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load timeline (${res.status})`);
  return (await res.json()) as { events: TimelineEvent[]; total: number };
}

export interface CreateNoticeBody {
  client_id: string;
  registration_id: string;
  law: "GST" | "IT";
  document_type: string;
  due_date?: string;
  issue_date?: string;
  hearing_date?: string;
  financial_year?: string;
  assessment_year?: string;
  authority?: string;
  din_or_rfn?: string;
  notice_number?: string;
  issue?: string;
  assigned_to?: string;
}

export async function createNotice(
  body: CreateNoticeBody,
): Promise<{ notice_id: string; matter_id: string }> {
  const res = await fetch("/api/notices", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `create failed (${res.status})`);
  }
  return (await res.json()) as { notice_id: string; matter_id: string };
}

export interface SearchResult {
  query: string;
  clients: Array<{
    client_id: string;
    legal_name: string;
    pan: string;
    entity_type: string | null;
  }>;
  notices: Array<{
    notice_id: string;
    document_type: string | null;
    due_date: string | null;
    lifecycle_status: string;
    din_or_rfn: string | null;
    issue: string | null;
    client_legal_name: string;
  }>;
}

export async function search(q: string): Promise<SearchResult> {
  const res = await fetch(`/api/search?q=${encodeURIComponent(q)}`, {
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`search failed (${res.status})`);
  return (await res.json()) as SearchResult;
}

export async function logout(): Promise<void> {
  await fetch("/api/logout", { method: "POST" });
}

// ---- Drafts (Sprint 5) ----------------------------------------------------

export interface DraftSection {
  num: number;
  title: string;
  body_html: string;
}

export interface DraftCitation {
  citation_id: string;
  case_name: string;
  citation_string: string | null;
  paragraph_referenced: string | null;
  proposition_for_which_cited: string | null;
  status: "VERIFIED" | "VERIFIED_PARTIAL" | "UNVERIFIED" | "STRIPPED";
  source_url: string | null;
  verification_tier: number | null;
  verified_paragraph_text: string | null;
  proposition_match_confidence: number | null;
  verified_at: string | null;
  action_taken: "passed" | "flagged" | "stripped" | null;
}

export interface DraftSummary {
  draft_id: string;
  version: number;
  status: string;
  tone: string | null;
  model_used: string | null;
  prompt_version: string | null;
  citation_summary: Record<string, number> | null;
  generated_at: string | null;
  generated_by_name: string | null;
}

export interface DraftDetail extends DraftSummary {
  matter_id: string;
  sections: DraftSection[];
  paragraph_to_source_map: Array<Record<string, unknown>>;
  internal_partner_note: string | null;
  edits_log: Array<Record<string, unknown>>;
  citations: DraftCitation[];
}

export interface GenerateDraftBody {
  tone?: "formal" | "assertive" | "conciliatory";
  partner_instructions?: string;
  include_cross_registration?: boolean;
}

export async function generateDraft(
  noticeId: string,
  body: GenerateDraftBody,
): Promise<{
  draft_id: string;
  version: number;
  citation_summary: Record<string, number>;
  sections_kept: number;
}> {
  const res = await fetch(`/api/notices/${noticeId}/draft`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `draft generation failed (${res.status})`);
  }
  return (await res.json()) as {
    draft_id: string;
    version: number;
    citation_summary: Record<string, number>;
    sections_kept: number;
  };
}

export async function fetchDraftVersions(
  matterId: string,
): Promise<{ drafts: DraftSummary[]; total: number }> {
  const res = await fetch(`/api/matters/${matterId}/drafts`, { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load drafts (${res.status})`);
  const data = await res.json();
  if (Array.isArray(data)) {
    return { drafts: data, total: data.length };
  }
  return { drafts: data?.drafts ?? [], total: data?.total ?? 0 };
}

export async function fetchDraft(id: string): Promise<DraftDetail> {
  const res = await fetch(`/api/drafts/${id}`, { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load draft (${res.status})`);
  return (await res.json()) as DraftDetail;
}

export interface EditDraftSectionBody {
  section_num: number;
  body_html: string;
  internal_partner_note?: string;
}

export async function editDraftSection(
  draftId: string,
  body: EditDraftSectionBody,
): Promise<{
  draft_id: string;
  version?: number;
  source_draft_id?: string;
  changed: boolean;
}> {
  const res = await fetch(`/api/drafts/${draftId}/section`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `edit failed (${res.status})`);
  }
  return (await res.json()) as {
    draft_id: string;
    version?: number;
    source_draft_id?: string;
    changed: boolean;
  };
}

// ---- Matter documents (Sprint 5 slice 8) ---------------------------------

export type DocumentLifecycle =
  | "requested"
  | "received"
  | "validated"
  | "used_in_draft"
  | "approved"
  | "submitted"
  | "acknowledged"
  | "referenced_in_appeal";

export interface MatterDocument {
  document_id: string;
  filename: string;
  document_type: string | null;
  mime_type: string | null;
  size_bytes: number | null;
  lifecycle_stage: DocumentLifecycle | null;
  uploaded_at: string | null;
  uploaded_by_name: string | null;
}

export async function fetchMatterDocuments(
  matterId: string,
): Promise<{ documents: MatterDocument[]; total: number }> {
  const res = await fetch(`/api/matters/${matterId}/documents`, { cache: "no-store" });
  if (!res.ok) throw new Error(`failed to load documents (${res.status})`);
  return (await res.json()) as { documents: MatterDocument[]; total: number };
}

export async function uploadMatterDocument(
  matterId: string,
  file: File,
  documentType = "supporting",
): Promise<{ document_id: string; filename: string; size_bytes: number }> {
  const fd = new FormData();
  fd.append("file", file);
  const url = `/api/matters/${matterId}/documents?document_type=${encodeURIComponent(documentType)}`;
  const res = await fetch(url, { method: "POST", body: fd });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `upload failed (${res.status})`);
  }
  return (await res.json()) as { document_id: string; filename: string; size_bytes: number };
}

export async function updateDocument(
  documentId: string,
  body: { document_type?: string; lifecycle_stage?: DocumentLifecycle },
): Promise<{ document_id: string; updated_fields: string[] }> {
  const res = await fetch(`/api/documents/${documentId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `update failed (${res.status})`);
  }
  return (await res.json()) as { document_id: string; updated_fields: string[] };
}

export interface AddRegistrationBody {
  client_id: string;
  registration_type: "IT" | "GST";
  identifier_value: string;
  state_code?: string | null;
  state_name?: string | null;
  auto_route_inbox_id?: string | null;
}

export function addRegistration(
  clientId: string,
  body: AddRegistrationBody,
): Promise<{ registration_id: string }> {
  return postJson(`/api/registrations/${clientId}`, body);
}

// ---- Triage --------------------------------------------------------------

export type TriageStatus =
  | "not_started"
  | "generating"
  | "ready_to_gather"
  | "ready_to_draft"
  | "drafted";

export type ChecklistItemStatus = "pending" | "uploaded" | "not_applicable";

export interface ChecklistItem {
  requirement_id: string;
  label: string;
  rationale: string;
  doc_type: string | null;
  is_required: boolean;
  status: ChecklistItemStatus;
  position: number;
  marked_na_reason: string | null;
  document_id: string | null;
  document_filename: string | null;
}

export interface ClientEmailStatus {
  status: "sent" | "skipped" | "failed";
  to?: string | null;
  reason?: string;
  provider?: string;
  message_id?: string;
}

export interface TriagePayload {
  notice_id: string;
  status: TriageStatus;
  summary: string | null;
  generated_at: string | null;
  prompt_version: string | null;
  model: string | null;
  provider_name: string | null;
  checklist: ChecklistItem[];
  // Only present on the POST /triage response (the GET endpoint does not
  // re-send the email and so does not surface a status). Undefined → ignore.
  client_email_status?: ClientEmailStatus;
}

export async function fetchTriage(noticeId: string): Promise<TriagePayload> {
  const res = await fetch(`/api/notices/${noticeId}/triage`, { cache: "no-store" });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `fetch triage failed (${res.status})`);
  }
  return (await res.json()) as TriagePayload;
}

export async function runTriage(noticeId: string): Promise<TriagePayload> {
  const res = await fetch(`/api/notices/${noticeId}/triage`, { method: "POST" });
  if (!res.ok) {
    const j = (await res.json().catch(() => null)) as { error?: { message?: string } } | null;
    throw new Error(j?.error?.message ?? `triage failed (${res.status})`);
  }
  return (await res.json()) as TriagePayload;
}

export async function attachDocumentToChecklistItem(
  noticeId: string,
  requirementId: string,
  documentId: string,
): Promise<TriagePayload> {
  return postJson(
    `/api/notices/${noticeId}/checklist/${requirementId}/attach`,
    { document_id: documentId },
  );
}

export async function markChecklistItemNotApplicable(
  noticeId: string,
  requirementId: string,
  reason: string,
): Promise<TriagePayload> {
  return postJson(
    `/api/notices/${noticeId}/checklist/${requirementId}/mark-na`,
    { reason },
  );
}
