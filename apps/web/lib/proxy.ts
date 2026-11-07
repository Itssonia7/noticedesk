// Server-side helper that forwards a browser request to the FastAPI with
// the dev-session cookie translated into X-Dev-* headers. Once Clerk is
// wired in, this file goes away — client components will hold a Clerk JWT
// and hit the API directly.
//
// We forward Content-Type / Content-Length from the inbound request so
// multipart uploads keep their boundary header, and we buffer the body
// instead of streaming it because streaming a Web ReadableStream through
// undici has been flaky across Node versions for `duplex: "half"`.

import { NextResponse } from "next/server";

import { readDevSession } from "@/lib/session";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:9090";

const HEADERS_TO_FORWARD: ReadonlySet<string> = new Set([
  "content-type",
  "accept",
]);

export async function proxyToApi(
  request: Request,
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const session = readDevSession();
  if (!session) {
    return NextResponse.json(
      { error: { code: "unauthorized", message: "no session" } },
      { status: 401 },
    );
  }
  const url = new URL(request.url);
  const target = new URL(`${API_BASE}${path}`);
  url.searchParams.forEach((v, k) => target.searchParams.set(k, v));

  const headers = new Headers(init.headers);
  request.headers.forEach((v, k) => {
    if (HEADERS_TO_FORWARD.has(k.toLowerCase())) headers.set(k, v);
  });
  headers.set("X-Dev-User-Id", session.user_id);
  headers.set("X-Dev-Tenant-Id", session.tenant_id);

  const method = init.method ?? request.method;
  let body: BodyInit | undefined = init.body as BodyInit | undefined;
  if (body === undefined && method !== "GET" && method !== "HEAD") {
    // Read the inbound body once. For 50 MB uploads this buffers fully —
    // acceptable for the dev path; production swaps Clerk in and removes
    // this proxy entirely.
    body = await request.arrayBuffer();
  }

  const fetchInit: RequestInit = { method, headers };
  if (body !== undefined) fetchInit.body = body;
  const upstream = await fetch(target.toString(), fetchInit);
  const contentType = upstream.headers.get("content-type") ?? "application/json";
  const responseBody = await upstream.arrayBuffer();
  const responseHeaders: Record<string, string> = { "content-type": contentType };
  // Pass through Content-Disposition so .docx exports trigger a native
  // file download in the browser instead of rendering as bytes.
  const cd = upstream.headers.get("content-disposition");
  if (cd) responseHeaders["content-disposition"] = cd;
  return new NextResponse(responseBody, {
    status: upstream.status,
    headers: responseHeaders,
  });
}
