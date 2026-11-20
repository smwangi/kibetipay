"use client";

import Link from "next/link";
import { useEffect, useState, type SubmitEvent } from "react";
import { useSWRConfig } from "swr";
import { TERMINAL_TRANSFER } from "@/components/transfers";
import { Button, Card, Dl, Field, Notice, PageHeader, Spinner, StatusBadge } from "@/components/ui";
import { api, newIdempotencyKey } from "@/lib/api";
import { formatAmount, formatMoney } from "@/lib/format";
import { useTransfer, useWallet } from "@/lib/hooks";
import type { TransferAccepted } from "@/lib/types";

const AMOUNT = /^\d{1,9}(\.\d{1,2})?$/;

export default function SendPage() {
  const { data: wallet } = useWallet();
  const [key, setKey] = useState(newIdempotencyKey);
  const [accepted, setAccepted] = useState<TransferAccepted | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ recipient?: string; amount?: string }>({});
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const recipient = String(form.get("recipient") ?? "").trim();
    const amount = String(form.get("amount") ?? "").trim();
    const note = String(form.get("note") ?? "").trim();

    const errors: typeof fieldErrors = {};
    if (!recipient) errors.recipient = "Enter who you are paying";
    if (!AMOUNT.test(amount) || Number(amount) < 1) errors.amount = "Enter an amount of at least 1.00, with up to 2 decimals";
    else if (wallet && Number(amount) * 100 > wallet.balance.minor) errors.amount = "That is more than your balance";
    setFieldErrors(errors);
    if (Object.keys(errors).length) return;

    setBusy(true);
    setError(null);
    try {
      // The same key is reused if the user retries after a network error, so money moves once.
      const result = await api<TransferAccepted>("/transfers", {
        method: "POST",
        idempotencyKey: key,
        body: { recipient, amount: Number(amount), note: note || undefined },
      });
      setAccepted(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The transfer could not be sent.");
    } finally {
      setBusy(false);
    }
  }

  if (accepted) {
    return (
      <TransferProgress
        accepted={accepted}
        onAnother={() => {
          setAccepted(null);
          setKey(newIdempotencyKey());
        }}
      />
    );
  }

  return (
    <>
      <PageHeader title="Send money" description="Pay another KibetiPay user instantly." />
      <Card className="max-w-lg">
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <Field id="recipient" name="recipient" label="Recipient username" placeholder="e.g. bob" maxLength={64} autoComplete="off" error={fieldErrors.recipient} />
          <Field
            id="amount"
            name="amount"
            label="Amount"
            inputMode="decimal"
            placeholder="0.00"
            error={fieldErrors.amount}
            hint={wallet ? `Available: ${formatMoney(wallet.balance)}` : undefined}
          />
          <Field id="note" name="note" label="Note (optional)" placeholder="What's it for?" maxLength={140} />
          {error && <Notice tone="danger">{error}</Notice>}
          <Button type="submit" busy={busy} className="w-full">Send money</Button>
        </form>
      </Card>
    </>
  );
}

function TransferProgress({ accepted, onAnother }: { accepted: TransferAccepted; onAnother: () => void }) {
  const { mutate } = useSWRConfig();
  // Poll until the risk check finishes; SWR stops polling once a final status arrives.
  const { data: transfer } = useTransfer(accepted.transferId, (latest) =>
    latest && TERMINAL_TRANSFER.has(latest.status) ? 0 : 1500,
  );
  const status = transfer?.status ?? "PENDING_RISK";
  const done = TERMINAL_TRANSFER.has(status);
  useEffect(() => {
    if (done) {
      // Balance and lists changed: refresh everything that shows them.
      void mutate((k) => typeof k === "string" && (k.startsWith("/wallets") || k.startsWith("/transfers")));
    }
  }, [done, mutate]);

  const message = {
    PENDING_RISK: { tone: "info", text: "Checking your transfer…" },
    IN_REVIEW: { tone: "warning", text: "Your transfer is being reviewed by our team. The money stays in your wallet until it is approved." },
    COMPLETED: { tone: "success", text: "Money sent." },
    REJECTED: { tone: "danger", text: "This transfer was declined by our security checks. No money left your wallet." },
    FAILED: { tone: "danger", text: transfer?.failureReason ?? "The transfer failed. No money left your wallet." },
  }[status] as { tone: "info" | "warning" | "success" | "danger"; text: string };

  return (
    <>
      <PageHeader title="Send money" />
      <Card className="max-w-lg space-y-5">
        <div className="flex items-center gap-3">
          {!TERMINAL_TRANSFER.has(status) && status !== "IN_REVIEW" && <Spinner className="text-brand" />}
          <StatusBadge status={status} />
          {accepted.replayed && <span className="text-xs text-muted">Already submitted earlier</span>}
        </div>
        <Notice tone={message.tone}>{message.text}</Notice>
        {transfer && (
          <Dl
            items={[
              ["To", transfer.recipientId],
              ["Amount", formatAmount(transfer.amount, transfer.currency)],
              ...(transfer.note ? ([["Note", transfer.note]] as [string, string][]) : []),
              ["Reference", <span key="ref" className="font-mono">{transfer.txnRef}</span>],
            ]}
          />
        )}
        <div className="flex gap-3">
          <Button onClick={onAnother}>Send another</Button>
          <Link href="/activity" className="inline-flex h-11 items-center rounded-xl px-4 text-sm font-medium hover:bg-surface-2">
            View activity
          </Link>
        </div>
      </Card>
    </>
  );
}
