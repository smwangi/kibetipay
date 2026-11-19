import type { Metadata } from "next";
import { Suspense } from "react";
import { Logo } from "@/components/icons";
import { LoginForm } from "./login-form";

export const metadata: Metadata = { title: "Sign in" };

export default function LoginPage() {
  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-12">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex justify-center">
          <Logo />
        </div>
        <div className="rounded-2xl border border-border bg-surface p-6 shadow-sm">
          <h1 className="text-xl font-semibold">Sign in to your wallet</h1>
          <p className="mt-1 text-sm text-muted">Send money, top up and manage your loans.</p>
          <Suspense>
            <LoginForm />
          </Suspense>
        </div>
        {process.env.NODE_ENV !== "production" && (
          <p className="mt-4 text-center text-xs text-muted">
            Development users: <code>alice</code> / <code>alice-dev</code>, <code>bob</code> / <code>bob-dev</code>
          </p>
        )}
      </div>
    </main>
  );
}
