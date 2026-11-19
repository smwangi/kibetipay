"use client";

/** Errors carry the backend's ProblemDetail fields (`detail`, and `code` for business rules). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
  ) {
    super(message);
  }
}

type Options = { method?: "GET" | "POST" | "PUT" | "DELETE"; body?: unknown; idempotencyKey?: string };

/** Calls the customer API through the same-origin proxy, e.g. api("/wallets/me"). */
export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;

  const response = await fetch(`/api/kp${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });

  if (response.status === 401) {
    await fetch("/api/session", { method: "DELETE" });
    // A full reload (not router.push) also drops every cached SWR response of the old session.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname)}`);
    throw new ApiError(401, "Your session has ended. Please sign in again.");
  }
  if (!response.ok) {
    let detail = `Request failed (HTTP ${response.status})`;
    let code: string | undefined;
    try {
      const problem = await response.json();
      if (typeof problem.detail === "string") detail = problem.detail;
      if (typeof problem.code === "string") code = problem.code;
    } catch {
      // not JSON; keep the generic message
    }
    throw new ApiError(response.status, friendly(code, detail), code);
  }
  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const fetcher = <T,>(path: string) => api<T>(path);

/** Stable idempotency keys: generate once per form, reuse on retry so money never moves twice. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

const MESSAGES: Record<string, string> = {
  insufficient_funds: "You don't have enough money in your wallet for this.",
  wallet_not_found: "That person doesn't have a KibetiPay wallet.",
  self_transfer: "You can't send money to yourself.",
  idempotency_key_reused: "This request clashed with an earlier one. Please start again.",
  currency_mismatch: "That currency isn't supported.",
};

function friendly(code: string | undefined, detail: string): string {
  if (code && MESSAGES[code]) return MESSAGES[code];
  if (detail === "Validation failure") return "Please check the details you entered.";
  return detail;
}
