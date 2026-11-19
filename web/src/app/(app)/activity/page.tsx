"use client";

import { useState, type FormEvent } from "react";
import { TransferRow } from "@/components/transfers";
import { Button, Card, cn, Dl, EmptyState, ErrorNote, Loading, Notice, PageHeader, StatusBadge } from "@/components/ui";
import { api, ApiError } from "@/lib/api";
import { describeReference, formatAmount, formatDateTime, formatMoney } from "@/lib/format";
import { useMe, useStatement, useTransfers } from "@/lib/hooks";
import type { Transfer } from "@/lib/types";

export default function ActivityPage() {
  const [tab, setTab] = useState<"transfers" | "statement">("transfers");
  return (
    <>
      <PageHeader title="Activity" description="Your transfers and every movement on your wallet." />
      <ReferenceLookup />
      <div role="tablist" aria-label="Activity view" className="mb-4 inline-flex rounded-xl border border-border bg-surface p-1">
        {(
          [
            ["transfers", "Transfers"],
            ["statement", "Statement"],
          ] as const
        ).map(([value, label]) => (
          <button
            key={value}
            role="tab"
            aria-selected={tab === value}
            onClick={() => setTab(value)}
            className={cn("rounded-lg px-4 py-2 text-sm font-medium", tab === value ? "bg-brand text-on-brand" : "text-muted hover:text-text")}
          >
            {label}
          </button>
        ))}
      </div>
      {tab === "transfers" ? <Transfers /> : <Statement />}
    </>
  );
}

function Transfers() {
  const { data: me } = useMe();
  const { data, error, isLoading } = useTransfers(50);
  return (
    <Card>
      {isLoading ? (
        <Loading />
      ) : error ? (
        <ErrorNote error={error} />
      ) : !data?.length ? (
        <EmptyState title="No transfers yet" />
      ) : (
        <ul className="divide-y divide-border">
          {data.map((t) => <TransferRow key={t.id} transfer={t} me={me?.username} />)}
        </ul>
      )}
    </Card>
  );
}

function Statement() {
  const { data, error, isLoading } = useStatement(100);
  return (
    <Card className="p-0">
      {isLoading ? (
        <div className="px-5"><Loading /></div>
      ) : error ? (
        <div className="p-5"><ErrorNote error={error} /></div>
      ) : !data?.length ? (
        <div className="p-5"><EmptyState title="No ledger entries yet" /></div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase tracking-wide text-muted">
              <tr className="border-b border-border">
                <th className="px-5 py-3 font-medium">Date</th>
                <th className="px-5 py-3 font-medium">Description</th>
                <th className="px-5 py-3 text-right font-medium">Amount</th>
                <th className="px-5 py-3 text-right font-medium">Balance</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {data.map((line) => (
                <tr key={`${line.journalId}-${line.direction}`}>
                  <td className="whitespace-nowrap px-5 py-3 text-muted">{formatDateTime(line.at)}</td>
                  <td className="px-5 py-3">{describeReference(line.reference)}</td>
                  <td className={cn("tabular whitespace-nowrap px-5 py-3 text-right font-medium", line.direction === "CREDIT" && "text-success")}>
                    {line.direction === "CREDIT" ? "+" : "−"}
                    {formatMoney(line.amount)}
                  </td>
                  <td className="tabular whitespace-nowrap px-5 py-3 text-right text-muted">{formatMoney(line.balanceAfter)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

/** Customers quote receipt references like KP7H3QX9M2AB to support; let them look one up too. */
function ReferenceLookup() {
  const [result, setResult] = useState<Transfer | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const ref = String(new FormData(event.currentTarget).get("ref") ?? "").trim().toUpperCase();
    if (!ref) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await api<Transfer>(`/transfers/ref/${encodeURIComponent(ref)}`));
    } catch (e) {
      setError(e instanceof ApiError && e.status === 404 ? `No transfer found with reference ${ref}.` : "Lookup failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mb-6">
      <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-3">
        <div className="min-w-48 flex-1 space-y-1.5">
          <label htmlFor="ref" className="block text-sm font-medium">Find a transfer by reference</label>
          <input
            id="ref"
            name="ref"
            placeholder="KP7H3QX9M2AB"
            maxLength={16}
            className="h-11 w-full rounded-xl border border-border bg-surface px-3 font-mono uppercase outline-none focus:border-brand focus:ring-2 focus:ring-brand-soft"
          />
        </div>
        <Button type="submit" variant="secondary" busy={busy}>Find</Button>
      </form>
      {error && <div className="mt-4"><Notice tone="warning">{error}</Notice></div>}
      {result && (
        <div className="mt-4">
          <Dl
            items={[
              ["Status", <StatusBadge key="s" status={result.status} />],
              ["From", result.initiatorId],
              ["To", result.recipientId],
              ["Amount", formatAmount(result.amount, result.currency)],
              ["Date", formatDateTime(result.createdAt)],
            ]}
          />
        </div>
      )}
    </Card>
  );
}
