"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type SubmitEvent } from "react";
import { TransferRow } from "@/components/transfers";
import { Button, Card, cn, Dl, EmptyState, ErrorNote, Loading, Notice, PageHeader, StatusBadge } from "@/components/ui";
import { api, ApiError } from "@/lib/api";
import {
  describeStatementLine,
  describeTopUpStatus,
  formatAmount,
  formatDate,
  formatDateTime,
  formatMoney,
  nairobiDate,
} from "@/lib/format";
import { type DateRange, useMe, useStatementHistory, useTopUpHistory, useTransferHistory } from "@/lib/hooks";
import type { Transfer } from "@/lib/types";

const TABS = [
  ["transfers", "Transfers"],
  ["statement", "Statement"],
  ["topups", "Top-ups"],
] as const;
type Tab = (typeof TABS)[number][0];

export default function ActivityPage() {
  return (
    <>
      <PageHeader title="Activity" description="Your transfers, top-ups and every movement on your wallet." />
      <ReferenceLookup />
      <Suspense fallback={<Card><Loading /></Card>}>
        <History />
      </Suspense>
    </>
  );
}

/** Tab and date range live in the URL, so the dashboard can link straight to e.g. ?tab=topups. */
function History() {
  const router = useRouter();
  const params = useSearchParams();
  const tab: Tab = (TABS.find(([value]) => value === params.get("tab"))?.[0] ?? "transfers") as Tab;
  const range: DateRange = { from: params.get("from") ?? undefined, to: params.get("to") ?? undefined };

  function update(next: Record<string, string | undefined>) {
    const query = new URLSearchParams(params.toString());
    for (const [key, value] of Object.entries(next)) {
      if (value) query.set(key, value);
      else query.delete(key);
    }
    router.replace(`/activity?${query}`, { scroll: false });
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div role="tablist" aria-label="Activity view" className="inline-flex rounded-xl border border-border bg-surface p-1">
          {TABS.map(([value, label]) => (
            <button
              key={value}
              role="tab"
              aria-selected={tab === value}
              onClick={() => update({ tab: value })}
              className={cn("rounded-lg px-4 py-2 text-sm font-medium", tab === value ? "bg-brand text-on-brand" : "text-muted hover:text-text")}
            >
              {label}
            </button>
          ))}
        </div>
        <RangeFilter range={range} onChange={(r) => update({ from: r.from, to: r.to })} />
      </div>
      {tab === "transfers" && <Transfers range={range} />}
      {tab === "statement" && <Statement range={range} />}
      {tab === "topups" && <TopUps range={range} />}
    </>
  );
}

const PRESETS = [
  ["all", "All time", undefined],
  ["7", "Last 7 days", 6],
  ["30", "Last 30 days", 29],
  ["90", "Last 90 days", 89],
] as const;

/** Presets first; a custom range covers "show me March" for reconciling an old month. */
function RangeFilter({ range, onChange }: { range: DateRange; onChange: (range: DateRange) => void }) {
  const today = nairobiDate();
  const preset =
    !range.from && !range.to
      ? "all"
      : (PRESETS.find(([, , days]) => days !== undefined && range.from === nairobiDate(-days) && (!range.to || range.to === today))?.[0] ??
        "custom");
  const inputClass =
    "h-10 rounded-xl border border-border bg-surface px-3 text-sm outline-none focus:border-brand focus:ring-2 focus:ring-brand-soft";
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <label htmlFor="range" className="sr-only">Date range</label>
      <select
        id="range"
        value={preset}
        onChange={(e) => {
          const chosen = PRESETS.find(([value]) => value === e.target.value);
          if (chosen) onChange(chosen[2] === undefined ? {} : { from: nairobiDate(-chosen[2]) });
          else onChange({ from: range.from ?? nairobiDate(-29), to: range.to ?? today });
        }}
        className={inputClass}
      >
        {PRESETS.map(([value, label]) => (
          <option key={value} value={value}>{label}</option>
        ))}
        <option value="custom">Custom range…</option>
      </select>
      {preset === "custom" && (
        <>
          <label className="sr-only" htmlFor="from">From</label>
          <input id="from" type="date" max={range.to ?? today} value={range.from ?? ""} onChange={(e) => onChange({ ...range, from: e.target.value || undefined })} className={inputClass} />
          <span className="text-muted">to</span>
          <label className="sr-only" htmlFor="to">To</label>
          <input id="to" type="date" min={range.from} max={today} value={range.to ?? ""} onChange={(e) => onChange({ ...range, to: e.target.value || undefined })} className={inputClass} />
        </>
      )}
    </div>
  );
}

function LoadMore({ hasMore, busy, onClick }: { hasMore: boolean; busy: boolean; onClick: () => void }) {
  if (!hasMore) return null;
  return (
    <div className="flex justify-center border-t border-border p-4">
      <Button variant="secondary" busy={busy} onClick={onClick}>Load more</Button>
    </div>
  );
}

function rangeEmptyTitle(range: DateRange, what: string) {
  return range.from || range.to ? `No ${what} in this period` : `No ${what} yet`;
}

function Transfers({ range }: { range: DateRange }) {
  const { data: me } = useMe();
  const { items, error, isLoading, isLoadingMore, hasMore, loadMore } = useTransferHistory(range);
  return (
    <Card className="p-0">
      {isLoading ? (
        <div className="px-5"><Loading /></div>
      ) : error ? (
        <div className="p-5"><ErrorNote error={error} /></div>
      ) : !items.length ? (
        <div className="p-5"><EmptyState title={rangeEmptyTitle(range, "transfers")} /></div>
      ) : (
        <>
          <ul className="divide-y divide-border px-5">
            {items.map((t) => <TransferRow key={t.id} transfer={t} me={me?.username} />)}
          </ul>
          <LoadMore hasMore={hasMore} busy={isLoadingMore} onClick={loadMore} />
        </>
      )}
    </Card>
  );
}

function Statement({ range }: { range: DateRange }) {
  const { items, error, isLoading, isLoadingMore, hasMore, loadMore } = useStatementHistory(range);
  return (
    <Card className="p-0">
      {isLoading ? (
        <div className="px-5"><Loading /></div>
      ) : error ? (
        <div className="p-5"><ErrorNote error={error} /></div>
      ) : !items.length ? (
        <div className="p-5"><EmptyState title={rangeEmptyTitle(range, "ledger entries")} /></div>
      ) : (
        <>
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
                {items.map((line) => (
                  <tr key={line.entryId}>
                    <td className="whitespace-nowrap px-5 py-3 text-muted">{formatDateTime(line.at)}</td>
                    <td className="px-5 py-3">{describeStatementLine(line)}</td>
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
          <LoadMore hasMore={hasMore} busy={isLoadingMore} onClick={loadMore} />
        </>
      )}
    </Card>
  );
}

/** Every M-Pesa top-up, including ones that failed and so never reached the statement. */
function TopUps({ range }: { range: DateRange }) {
  const { items, error, isLoading, isLoadingMore, hasMore, loadMore } = useTopUpHistory(range);
  return (
    <Card className="p-0">
      {isLoading ? (
        <div className="px-5"><Loading /></div>
      ) : error ? (
        <div className="p-5"><ErrorNote error={error} /></div>
      ) : !items.length ? (
        <div className="p-5"><EmptyState title={rangeEmptyTitle(range, "top-ups")} /></div>
      ) : (
        <>
          <ul className="divide-y divide-border px-5">
            {items.map((c) => (
              <li key={c.id} className="flex items-center gap-3 py-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium">M-Pesa top-up from {c.msisdn}</p>
                  <p className="truncate text-xs text-muted">
                    {formatDateTime(c.createdAt)}
                    {c.receipt && <> · Receipt <span className="font-mono">{c.receipt}</span></>}
                  </p>
                </div>
                <div className="text-right">
                  <p className={cn("tabular font-semibold", c.status === "COMPLETED" ? "text-success" : "text-muted")}>
                    {c.status === "COMPLETED" ? "+" : ""}
                    {formatMoney(c.amount)}
                  </p>
                  <StatusBadge status={c.status} label={describeTopUpStatus(c.status)} />
                </div>
              </li>
            ))}
          </ul>
          <LoadMore hasMore={hasMore} busy={isLoadingMore} onClick={loadMore} />
        </>
      )}
    </Card>
  );
}

/** Customers quote receipt references like KP7H3QX9M2AB to support; let them look one up too. */
function ReferenceLookup() {
  const [result, setResult] = useState<Transfer | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: SubmitEvent<HTMLFormElement>) {
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
        <div className="mt-4 space-y-3">
          <Dl
            items={[
              ["Status", <StatusBadge key="s" status={result.status} />],
              ["From", result.initiatorId],
              ["To", result.recipientId],
              ["Amount", formatAmount(result.amount, result.currency)],
              ["Date", formatDate(result.createdAt)],
            ]}
          />
          {result.statusMessage && <p className="text-sm text-muted">{result.statusMessage}</p>}
        </div>
      )}
    </Card>
  );
}
