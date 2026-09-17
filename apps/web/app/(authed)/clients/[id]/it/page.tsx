"use client";

import { Plus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

import { YearGroup } from "@/components/clients/year-group";
import { AddNoticeModal } from "@/components/matters/add-notice-modal";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/toast";
import { fetchClient, fetchRegistrationNotices, type RegistrationNoticesResponse } from "@/lib/api";

interface Props {
  params: { id: string };
}

export default function ClientITDrilldownPage({ params }: Props) {
  const router = useRouter();
  const { toast } = useToast();
  const [data, setData] = useState<RegistrationNoticesResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);

  const load = useCallback(async () => {
    try {
      const client = await fetchClient(params.id);
      const itReg = client.registrations.find((r) => r.registration_type === "IT");
      if (!itReg) {
        setError("No Income Tax registration found for this client");
        return;
      }
      const next = await fetchRegistrationNotices(itReg.registration_id);
      setData(next);
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "failed to load");
    } finally {
      setLoading(false);
    }
  }, [params.id]);

  useEffect(() => {
    void load();
  }, [load]);

  if (loading) {
    return (
      <main className="mx-auto max-w-[1400px] px-10 py-8">
        <p className="py-12 text-center font-serif italic text-slate">Loading notices…</p>
      </main>
    );
  }

  if (error || !data) {
    return (
      <main className="mx-auto max-w-[1400px] px-10 py-8">
        <p className="rounded border border-alarm/30 bg-alarm-bg px-3 py-2 text-sm text-alarm">
          {error ?? "Not found"}
        </p>
      </main>
    );
  }

  // Group by assessment_year. Notices without an AY land in a "No AY" bucket.
  const buckets: Record<string, typeof data.notices> = {};
  for (const n of (data.notices || [])) {
    const key = n.assessment_year ?? "No AY";
    if (!buckets[key]) buckets[key] = [];
    buckets[key].push(n);
  }
  const years = Object.keys(buckets).sort().reverse();

  return (
    <main className="mx-auto max-w-[1400px] px-10 py-8 pb-16">
      <nav className="mb-3.5 flex items-center gap-2 text-[11px] font-medium uppercase tracking-[0.08em] text-slate">
        <a className="cursor-pointer hover:text-ink" onClick={() => router.push("/clients")}>
          Clients
        </a>
        <span className="text-slate-pale">›</span>
        <a
          className="cursor-pointer hover:text-ink"
          onClick={() => router.push(`/clients/${params.id}`)}
        >
          {data.client.legal_name}
        </a>
        <span className="text-slate-pale">›</span>
        <span className="font-semibold text-ink">Income Tax</span>
      </nav>

      <header className="mb-7 flex flex-wrap items-end justify-between gap-6">
        <div>
          <h1 className="font-serif text-[36px] font-medium tracking-tight text-navy-deep">
            Income Tax
          </h1>
          <p className="mt-1 font-serif text-[17px] italic text-slate">
            {data.client.legal_name} · PAN{" "}
            <span className="font-mono text-[15px]">{data.client.pan}</span> ·{" "}
            {data.notices.length} {data.notices.length === 1 ? "notice" : "notices"} across{" "}
            {years.length} {years.length === 1 ? "year" : "years"}
          </p>
        </div>
        <Button variant="gold" size="sm" onClick={() => setAddOpen(true)}>
          <Plus className="mr-1.5 h-3.5 w-3.5" aria-hidden />
          Add notice manually
        </Button>
      </header>

      {years.length === 0 ? (
        <div className="rounded-md border border-dashed border-slate-line bg-white p-10 text-center">
          <p className="font-serif text-[16px] text-navy-deep">No notices yet</p>
          <p className="mt-1 text-sm text-slate">
            Upload an IT notice from the inbox or add one manually.
          </p>
        </div>
      ) : (
        years.map((ay) => (
          <YearGroup
            key={ay}
            title={ay === "No AY" ? "No assessment year" : `Assessment Year ${ay}`}
            notices={buckets[ay]!}
          />
        ))
      )}

      <AddNoticeModal
        open={addOpen}
        clientId={data.client.client_id}
        registrationId={data.registration.registration_id}
        law="IT"
        onClose={() => setAddOpen(false)}
        onCreated={(noticeId) => {
          toast("Notice added", "success");
          setAddOpen(false);
          void load();
          router.push(`/matters/${noticeId}`);
        }}
      />
    </main>
  );
}
