"use client";

import { useEffect, useState } from "react";

import { cn } from "@/lib/cn";

interface ProvidersInfo {
  llm: { primary: string; secondary: string; model: string; is_stub: boolean };
  ocr: { primary: string; is_stub: boolean };
  citation: { primary: string; is_stub: boolean };
  auth: { provider: string; is_dev: boolean };
  storage: { backend: string; is_local: boolean };
  workflow_backend: string;
  environment: string;
}

// Thin status strip pinned above the topbar. Partners know at a glance
// whether the draft they're looking at came from a real LLM or the stub,
// so demos don't get misrepresented. Hidden in production (environment
// != "development" || every provider is real).
export function DemoBanner() {
  const [info, setInfo] = useState<ProvidersInfo | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch("/api/demo/providers", { cache: "no-store" });
        if (!res.ok) return;
        const data = (await res.json()) as ProvidersInfo;
        if (!cancelled) setInfo(data);
      } catch {
        // Banner is best-effort.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (!info || !info.llm) return null;

  const allReal =
    !info.llm.is_stub &&
    !info.ocr.is_stub &&
    !info.citation.is_stub &&
    !info.auth.is_dev &&
    !info.storage.is_local;
  if (allReal && info.environment !== "development") return null;

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-gold/30 bg-paper-warm px-6 py-1.5 text-[10.5px] font-medium uppercase tracking-[0.06em] text-gold-dark">
      <span className="font-bold">Demo mode</span>
      <Chip
        label="LLM"
        value={info.llm.is_stub ? "stub" : `Claude · ${info.llm.model}`}
        live={!info.llm.is_stub}
      />
      <Chip
        label="OCR"
        value={info.ocr.is_stub ? "stub" : info.ocr.primary}
        live={!info.ocr.is_stub}
      />
      <Chip
        label="Citations"
        value={info.citation.is_stub ? "stub (3 cases)" : info.citation.primary}
        live={!info.citation.is_stub}
      />
      <Chip
        label="Auth"
        value={info.auth.is_dev ? "dev-cookie" : info.auth.provider}
        live={!info.auth.is_dev}
      />
      <Chip
        label="Storage"
        value={info.storage.is_local ? "local /tmp" : info.storage.backend}
        live={!info.storage.is_local}
      />
    </div>
  );
}

function Chip({
  label,
  value,
  live,
}: {
  label: string;
  value: string;
  live: boolean;
}) {
  return (
    <span className="flex items-center gap-1.5">
      <span className="text-gold-dark/70">{label}</span>
      <span
        className={cn(
          "rounded-sm px-1.5 py-0.5 font-mono normal-case tracking-normal",
          live ? "bg-success/15 text-success" : "bg-slate-line text-slate",
        )}
      >
        {value}
      </span>
    </span>
  );
}
