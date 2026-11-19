import { backendFetch } from "@/lib/server/backend";
import { readSession } from "@/lib/server/session";

/**
 * Same-origin proxy: /api/kp/<path> -> <backend>/api/<path>, adding the session's credentials.
 * Only customer endpoints are reachable; admin and actuator paths are refused here as well as
 * by the backend's own role checks.
 */
const FORWARDED_REQUEST_HEADERS = ["content-type", "idempotency-key"];
const FORWARDED_RESPONSE_HEADERS = ["content-type", "location"];

async function forward(request: Request, ctx: RouteContext<"/api/kp/[...path]">) {
  const session = await readSession();
  if (!session) {
    return Response.json({ detail: "Not signed in" }, { status: 401 });
  }
  const { path } = await ctx.params;
  if (path.length === 0 || path[0] === "admin" || path.some((p) => p === ".." || p.includes("/"))) {
    return Response.json({ detail: "Not found" }, { status: 404 });
  }

  const search = new URL(request.url).search;
  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const hasBody = request.method !== "GET" && request.method !== "HEAD";

  let upstream: Response;
  try {
    upstream = await backendFetch(`/api/${path.map(encodeURIComponent).join("/")}${search}`, session, {
      method: request.method,
      headers,
      body: hasBody ? await request.text() : undefined,
    });
  } catch {
    return Response.json({ detail: "KibetiPay is unreachable. Is the backend running?" }, { status: 502 });
  }

  const responseHeaders = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }
  // A 401 from the backend means the stored password no longer works: the client signs out.
  return new Response(upstream.status === 204 ? null : upstream.body, {
    status: upstream.status,
    headers: responseHeaders,
  });
}

export const GET = forward;
export const POST = forward;
export const PUT = forward;
export const DELETE = forward;
