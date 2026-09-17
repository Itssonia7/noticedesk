"use client";

import type { InboxItem } from "@noticedesk/shared/inbox";
import { useState, useEffect } from "react";

import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { addClient, addRegistration, rejectInbox, fetchClients, fetchClient, routeManually } from "@/lib/api";
import type { ClientSummary, ClientRegistration } from "@/lib/api";

interface Props {
  item: InboxItem;
  onResolved: () => void;
}

export function AnomalyActions({ item, onResolved }: Props) {
  const [mode, setMode] = useState<
    | null
    | { kind: "add_client"; pan: string }
    | { kind: "add_registration"; gstin: string; state: string; clientId: string }
    | { kind: "reject" }
    | { kind: "route_manually" }
  >(null);

  const status = item.routing_status;
  const details = (item.routing_anomaly_details ?? {}) as Record<string, unknown>;

  const buttons: React.ReactNode[] = [];

  if (status === "client_not_found") {
    const pan = String(details.canonical_pan ?? "");
    buttons.push(
      <Button
        key="add-client"
        variant="secondary"
        size="sm"
        onClick={() => setMode({ kind: "add_client", pan })}
      >
        + Add client
      </Button>,
    );
  } else if (status === "new_gst_registration_detected") {
    const gstin = String(details.gstin ?? "");
    const state = String(details.state_code ?? "");
    const clientId = String(details.client_id ?? "");
    buttons.push(
      <Button
        key="add-reg"
        variant="secondary"
        size="sm"
        onClick={() => setMode({ kind: "add_registration", gstin, state, clientId })}
      >
        + Add registration
      </Button>,
    );
  } else if (status === "pan_gstin_mismatch") {
    // No auto-fix; partner reviews. We only offer rejection here.
    buttons.push(
      <span key="banner" className="text-xs text-red-700">
        PAN / GSTIN mismatch — partner review required
      </span>,
    );
  } else if (status === "no_identifier_found" || status === "manual_assignment") {
    buttons.push(
      <Button
        key="manual"
        variant="secondary"
        size="sm"
        onClick={() => setMode({ kind: "route_manually" })}
      >
        Assign manually
      </Button>,
    );
  }

  if (status !== "routed" && status !== "pending") {
    buttons.push(
      <Button
        key="reject"
        variant="ghost"
        size="sm"
        onClick={() => setMode({ kind: "reject" })}
      >
        Reject
      </Button>,
    );
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">{buttons}</div>

      {mode?.kind === "add_client" ? (
        <AddClientModal
          inboxId={item.inbox_id}
          initialPan={mode.pan}
          initialName={String(details.extracted_name ?? "")}
          onClose={() => setMode(null)}
          onResolved={onResolved}
        />
      ) : null}

      {mode?.kind === "add_registration" ? (
        <AddRegistrationModal
          inboxId={item.inbox_id}
          clientId={mode.clientId}
          initialGstin={mode.gstin}
          initialState={mode.state}
          onClose={() => setMode(null)}
          onResolved={onResolved}
        />
      ) : null}

      {mode?.kind === "reject" ? (
        <RejectModal
          inboxId={item.inbox_id}
          onClose={() => setMode(null)}
          onResolved={onResolved}
        />
      ) : null}

      {mode?.kind === "route_manually" ? (
        <ManualRouteModal
          inboxId={item.inbox_id}
          onClose={() => setMode(null)}
          onResolved={onResolved}
        />
      ) : null}
    </>
  );
}

function AddClientModal({
  inboxId,
  initialPan,
  initialName,
  onClose,
  onResolved,
}: {
  inboxId: string;
  initialPan: string;
  initialName: string;
  onClose: () => void;
  onResolved: () => void;
}) {
  const [pan, setPan] = useState(initialPan);
  const [name, setName] = useState(initialName);
  const [tradeName, setTradeName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await addClient({
        pan,
        legal_name: name,
        trade_name: tradeName || null,
        auto_route_inbox_id: inboxId,
      });
      onResolved();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "failed to add client");
      setSubmitting(false);
    }
  };

  return (
    <Dialog open title="Add client" onClose={onClose}>
      <form onSubmit={onSubmit} className="space-y-4">
        <div className="space-y-1">
          <label className="text-sm font-medium text-navy">PAN</label>
          <Input value={pan} onChange={(e) => setPan(e.target.value.toUpperCase())} required />
          <p className="text-xs text-slate-500">
            Pre-filled from the notice. Will be used as the canonical client identity.
          </p>
        </div>
        <div className="space-y-1">
          <label className="text-sm font-medium text-navy">Legal name</label>
          <Input value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="space-y-1">
          <label className="text-sm font-medium text-navy">Trade name (optional)</label>
          <Input value={tradeName} onChange={(e) => setTradeName(e.target.value)} />
        </div>
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={submitting}>
            {submitting ? "Adding…" : "Add client & re-route"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function AddRegistrationModal({
  inboxId,
  clientId,
  initialGstin,
  initialState,
  onClose,
  onResolved,
}: {
  inboxId: string;
  clientId: string;
  initialGstin: string;
  initialState: string;
  onClose: () => void;
  onResolved: () => void;
}) {
  const [gstin, setGstin] = useState(initialGstin);
  const [stateName, setStateName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await addRegistration(clientId, {
        client_id: clientId,
        registration_type: "GST",
        identifier_value: gstin,
        state_code: initialState,
        state_name: stateName || null,
        auto_route_inbox_id: inboxId,
      });
      onResolved();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "failed to add registration");
      setSubmitting(false);
    }
  };

  return (
    <Dialog open title="Add GST registration" onClose={onClose}>
      <form onSubmit={onSubmit} className="space-y-4">
        <div className="space-y-1">
          <label className="text-sm font-medium text-navy">GSTIN</label>
          <Input
            value={gstin}
            onChange={(e) => setGstin(e.target.value.toUpperCase())}
            required
          />
          <p className="text-xs text-slate-500">
            Pre-filled from the notice. Positions 3-12 must match the client&rsquo;s PAN.
          </p>
        </div>
        <div className="space-y-1">
          <label className="text-sm font-medium text-navy">State name (optional)</label>
          <Input value={stateName} onChange={(e) => setStateName(e.target.value)} />
        </div>
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={submitting}>
            {submitting ? "Adding…" : "Add registration & re-route"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function RejectModal({
  inboxId,
  onClose,
  onResolved,
}: {
  inboxId: string;
  onClose: () => void;
  onResolved: () => void;
}) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await rejectInbox(inboxId, reason);
      onResolved();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "failed to reject");
      setSubmitting(false);
    }
  };

  return (
    <Dialog open title="Reject document" onClose={onClose}>
      <form onSubmit={onSubmit} className="space-y-3">
        <p className="text-sm text-slate-700">
          Rejecting a document keeps the file but blocks it from being routed.
          The reason is logged.
        </p>
        <textarea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={3}
          className="w-full rounded border border-slate-200 p-2 text-sm"
          placeholder="Why are you rejecting this?"
          required
          minLength={5}
        />
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={submitting}>
            {submitting ? "Rejecting…" : "Reject"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function ManualRouteModal({
  inboxId,
  onClose,
  onResolved,
}: {
  inboxId: string;
  onClose: () => void;
  onResolved: () => void;
}) {
  const [clients, setClients] = useState<ClientSummary[]>([]);
  const [clientId, setClientId] = useState("");
  const [registrations, setRegistrations] = useState<ClientRegistration[]>([]);
  const [registrationId, setRegistrationId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchClients()
      .then((res) => {
        setClients(res.clients);
        setLoading(false);
      })
      .catch((err) => {
        setError(err.message);
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    if (!clientId) {
      setRegistrations([]);
      setRegistrationId("");
      return;
    }
    fetchClient(clientId)
      .then((res) => {
        const regs = res.registrations || [];
        setRegistrations(regs);
        if (regs.length > 0 && regs[0]) {
          setRegistrationId(regs[0].registration_id);
        } else {
          setRegistrationId("");
        }
      })
      .catch((err) => setError(err.message));
  }, [clientId]);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!clientId || !registrationId) return;
    setSubmitting(true);
    setError(null);
    try {
      await routeManually(inboxId, {
        client_id: clientId,
        registration_id: registrationId,
        override_reason: "Manual assignment from inbox",
      });
      onResolved();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "failed to route");
      setSubmitting(false);
    }
  };

  return (
    <Dialog open title="Assign manually" onClose={onClose}>
      <form onSubmit={onSubmit} className="space-y-4">
        {loading ? (
          <p className="text-sm text-slate-500">Loading clients...</p>
        ) : (
          <>
            <div className="space-y-1">
              <label className="text-sm font-medium text-navy">Client</label>
              <select
                value={clientId}
                onChange={(e) => setClientId(e.target.value)}
                required
                className="flex h-10 w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm"
              >
                <option value="" disabled>Select a client</option>
                {clients.map((c) => (
                  <option key={c.client_id} value={c.client_id}>
                    {c.legal_name} ({c.pan})
                  </option>
                ))}
              </select>
            </div>
            
            {clientId && (
              <div className="space-y-1">
                <label className="text-sm font-medium text-navy">Registration</label>
                <select
                  value={registrationId}
                  onChange={(e) => setRegistrationId(e.target.value)}
                  required
                  className="flex h-10 w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm"
                >
                  {registrations.length === 0 && <option value="" disabled>No registrations found</option>}
                  {registrations.map((r) => (
                    <option key={r.registration_id} value={r.registration_id}>
                      {r.registration_type} - {r.identifier_value} {r.state_name ? `(${r.state_name})` : ""}
                    </option>
                  ))}
                </select>
              </div>
            )}
          </>
        )}
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={submitting || !clientId || !registrationId}>
            {submitting ? "Assigning…" : "Assign"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
