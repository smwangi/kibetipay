"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button, Field, Notice } from "@/components/ui";

/** Messages the no-JavaScript sign-in may pass back in ?error=. Anything else is ignored. */
const KNOWN_ERRORS = new Set([
  "Enter your username and password",
  "Wrong username or password",
  "KibetiPay is unreachable. Is the backend running?",
]);

/** Only same-site paths are allowed as a post-login destination. */
function safeNext(value: string | null): string {
  return value && value.startsWith("/") && !value.startsWith("//") ? value : "/";
}

export function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/session", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: form.get("username"), password: form.get("password") }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => ({}));
        setError(problem.detail ?? "Sign-in failed");
        return;
      }
      router.replace(safeNext(params.get("next")));
      router.refresh();
    } catch {
      setError("Couldn't reach KibetiPay. Check your connection.");
    } finally {
      setBusy(false);
    }
  }

  return (
    // method/action matter: if JavaScript hasn't loaded, the browser posts the form itself.
    // Never let that fall back to GET, which would put the password in the URL.
    <form method="post" action="/api/session" onSubmit={onSubmit} className="mt-6 space-y-4">
      <input type="hidden" name="next" value={safeNext(params.get("next"))} />
      {!error && KNOWN_ERRORS.has(params.get("error") ?? "") && <Notice tone="danger">{params.get("error")}</Notice>}
      <Field id="username" name="username" label="Username" autoComplete="username" required autoFocus />
      <Field id="password" name="password" type="password" label="Password" autoComplete="current-password" required />
      {error && <Notice tone="danger">{error}</Notice>}
      <Button type="submit" busy={busy} className="w-full">
        Sign in
      </Button>
    </form>
  );
}
