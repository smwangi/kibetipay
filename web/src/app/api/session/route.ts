import { backendFetch } from "@/lib/server/backend";
import { clearSession, readSession, writeSession } from "@/lib/server/session";

/** Who is signed in. 401 when there is no valid session. */
export async function GET() {
  const session = await readSession();
  if (!session) {
    return Response.json({ detail: "Not signed in" }, { status: 401 });
  }
  return Response.json({ username: session.username });
}

type SignIn = { username: string; password: string; next: string };
type Outcome = { ok: true; username: string } | { ok: false; status: number; detail: string };

/** Only same-site paths are allowed as a post-login destination. */
function safeNext(value: unknown): string {
  return typeof value === "string" && value.startsWith("/") && !value.startsWith("//") ? value : "/";
}

async function signIn({ username, password }: SignIn): Promise<Outcome> {
  if (!username || !password || username.length > 64 || password.length > 256) {
    return { ok: false, status: 400, detail: "Enter your username and password" };
  }
  let probe: Response;
  try {
    // Any authenticated customer endpoint works; it must not depend on having a wallet.
    probe = await backendFetch("/api/notifications", { username, password });
  } catch {
    return { ok: false, status: 502, detail: "KibetiPay is unreachable. Is the backend running?" };
  }
  if (probe.status === 401) return { ok: false, status: 401, detail: "Wrong username or password" };
  if (!probe.ok) return { ok: false, status: 502, detail: `Sign-in check failed (HTTP ${probe.status})` };
  await writeSession(username, password);
  return { ok: true, username };
}

/**
 * Sign in. Accepts JSON (the login page's script) or a plain form post (the same page before
 * its script has loaded). A form post is answered with a redirect, never with the password in a URL.
 */
export async function POST(request: Request) {
  const isForm = (request.headers.get("content-type") ?? "").includes("application/x-www-form-urlencoded");
  let input: SignIn;
  try {
    if (isForm) {
      const form = await request.formData();
      input = { username: String(form.get("username") ?? "").trim(), password: String(form.get("password") ?? ""), next: safeNext(form.get("next")) };
    } else {
      const body = await request.json();
      input = {
        username: typeof body.username === "string" ? body.username.trim() : "",
        password: typeof body.password === "string" ? body.password : "",
        next: "/",
      };
    }
  } catch {
    return Response.json({ detail: "Invalid request" }, { status: 400 });
  }

  const outcome = await signIn(input);
  if (isForm) {
    const target = new URL(outcome.ok ? input.next : "/login", request.url);
    if (!outcome.ok) {
      target.searchParams.set("error", outcome.detail);
      if (input.next !== "/") target.searchParams.set("next", input.next);
    }
    return Response.redirect(target, 303);
  }
  return outcome.ok
    ? Response.json({ username: outcome.username })
    : Response.json({ detail: outcome.detail }, { status: outcome.status });
}

export async function DELETE() {
  await clearSession();
  return new Response(null, { status: 204 });
}
