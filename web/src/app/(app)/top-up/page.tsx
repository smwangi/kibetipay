"use client";

import { useEffect, useState, type SubmitEvent, type ReactNode } from "react";
import useSWR, { useSWRConfig } from "swr";
import { Button, Card, cn, Dl, Field, Notice, PageHeader, Spinner, StatusBadge } from "@/components/ui";
import { api, fetcher, newIdempotencyKey } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import { useWallet } from "@/lib/hooks";
import type { Collection, PostingReceipt } from "@/lib/types";

const AMOUNT = /^\d{1,9}(\.\d{1,2})?$/;
const SHOW_SIMULATED = process.env.NODE_ENV !== "production";

export default function TopUpPage() {
  const [mode, setMode] = useState<"mobile" | "simulated">("mobile");
  return (
    <>
      <PageHeader title="Top up" description="Add money to your KibetiPay wallet." />
      {SHOW_SIMULATED && (
        <div role="tablist" aria-label="Top-up method" className="mb-4 inline-flex rounded-xl border border-border bg-surface p-1">
          {(
            [
              ["mobile", "Mobile money"],
              ["simulated", "Simulated deposit"],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              role="tab"
              aria-selected={mode === value}
              onClick={() => setMode(value)}
              className={cn(
                "rounded-lg px-4 py-2 text-sm font-medium",
                mode === value ? "bg-brand text-on-brand" : "text-muted hover:text-text",
              )}
            >
              {label}
            </button>
          ))}
        </div>
      )}
      {mode === "mobile" ? <MobileMoneyTopUp /> : <SimulatedDeposit />}
    </>
  );
}

function parseAmount(value: string): string | null {
  return AMOUNT.test(value) && Number(value) >= 1 && Number(value) <= 150_000 ? null : "Enter an amount between 1.00 and 150,000.00";
}

function MobileMoneyTopUp() {
  const [key, setKey] = useState(newIdempotencyKey);
  const [collection, setCollection] = useState<Collection | null>(null);
  const [errors, setErrors] = useState<{ msisdn?: string; amount?: string }>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const msisdn = String(form.get("msisdn") ?? "").replace(/[\s-]/g, "").replace(/^\+/, "");
    const amount = String(form.get("amount") ?? "").trim();
    const next = {
      msisdn: /^\d{8,15}$/.test(msisdn) ? undefined : "Use international format without +, e.g. 254712345678",
      amount: parseAmount(amount) ?? undefined,
    };
    setErrors(next);
    if (next.msisdn || next.amount) return;

    setBusy(true);
    setError(null);
    try {
      setCollection(
        await api<Collection>("/mobile-money/collections", {
          method: "POST",
          idempotencyKey: key,
          body: { msisdn, amount: Number(amount) },
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "The top-up could not be started.");
    } finally {
      setBusy(false);
    }
  }

  if (collection) {
    return (
      <CollectionProgress
        initial={collection}
        onAnother={() => {
          setCollection(null);
          setKey(newIdempotencyKey());
        }}
      />
    );
  }

  return (
    <Card className="max-w-lg">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <p className="text-sm text-muted">
          We&apos;ll send a payment prompt to your phone. Your wallet is credited once the payment is confirmed.
        </p>
        <Field id="msisdn" name="msisdn" label="Phone number" inputMode="tel" autoComplete="tel" placeholder="254712345678" error={errors.msisdn} />
        <Field id="amount" name="amount" label="Amount" inputMode="decimal" placeholder="0.00" error={errors.amount} />
        {error && <Notice tone="danger">{error}</Notice>}
        <Button type="submit" busy={busy} className="w-full">Send payment prompt</Button>
      </form>
    </Card>
  );
}

const COLLECTION_DONE = new Set(["COMPLETED", "FAILED", "REJECTED"]);

function CollectionProgress({ initial, onAnother }: { initial: Collection; onAnother: () => void }) {
  const { mutate } = useSWRConfig();
  const { data } = useSWR<Collection>(`/mobile-money/collections/${initial.id}`, fetcher<Collection>, {
    fallbackData: initial,
    refreshInterval: (latest) => (latest && COLLECTION_DONE.has(latest.status) ? 0 : 3000),
  });
  const collection = data ?? initial;
  const done = COLLECTION_DONE.has(collection.status);
  useEffect(() => {
    // Any final outcome changes the top-up history; only success changes the balance.
    if (done) {
      void mutate((k) => typeof k === "string" && (k.includes("/wallets") || k.includes("/mobile-money/collections")));
    }
  }, [done, mutate]);

  const messages: Record<string, [tone: "info" | "success" | "warning" | "danger", text: string]> = {
    SUBMITTING: ["info", "Sending the payment prompt to your phone…"],
    PENDING: ["info", "Check your phone and enter your PIN to approve the payment. This page updates automatically."],
    UNKNOWN: ["warning", "We couldn't confirm whether the prompt reached your phone. Don't try again yet: if you were charged, the money will reach your wallet. Contact support if it doesn't arrive."],
    REJECTED: ["danger", "The mobile money provider declined this request. You have not been charged."],
    FAILED: ["danger", "The payment was not completed. You have not been charged."],
    COMPLETED: ["success", "Payment received. Your wallet has been topped up."],
  };
  const [tone, text] = messages[collection.status] ?? ["info", collection.status];

  return (
    <Card className="max-w-lg space-y-5">
      <div className="flex items-center gap-3">
        {!done && collection.status !== "UNKNOWN" && <Spinner className="text-brand" />}
        <StatusBadge status={collection.status} />
      </div>
      <Notice tone={tone}>{text}</Notice>
      <Dl
        items={[
          ["Provider", collection.provider],
          ...(collection.receipt ? ([["Receipt", <span key="r" className="font-mono">{collection.receipt}</span>]] as [string, ReactNode][]) : []),
        ]}
      />
      {done && <Button onClick={onAnother}>Top up again</Button>}
    </Card>
  );
}

/** Development shortcut: credits the wallet directly without any payment provider. */
function SimulatedDeposit() {
  const { data: wallet, mutate } = useWallet();
  const [key, setKey] = useState(newIdempotencyKey);
  const [amountError, setAmountError] = useState<string | undefined>();
  const [error, setError] = useState<string | null>(null);
  const [receipt, setReceipt] = useState<PostingReceipt | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const amount = String(new FormData(event.currentTarget).get("amount") ?? "").trim();
    const invalid = parseAmount(amount);
    setAmountError(invalid ?? undefined);
    if (invalid) return;
    setBusy(true);
    setError(null);
    try {
      setReceipt(await api<PostingReceipt>("/wallets/me/deposits", { method: "POST", idempotencyKey: key, body: { amount: Number(amount) } }));
      setKey(newIdempotencyKey());
      await mutate();
    } catch (e) {
      setError(e instanceof Error ? e.message : "The deposit failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="max-w-lg">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <Notice tone="warning">Development only: this credits your wallet without a real payment.</Notice>
        <Field id="sim-amount" name="amount" label="Amount" inputMode="decimal" placeholder="0.00" error={amountError} />
        {error && <Notice tone="danger">{error}</Notice>}
        {receipt && wallet && <Notice tone="success">Deposited. New balance: {formatMoney(wallet.balance)}</Notice>}
        <Button type="submit" busy={busy} className="w-full">Deposit</Button>
      </form>
    </Card>
  );
}
