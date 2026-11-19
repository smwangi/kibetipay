"use client";

import { useState } from "react";
import { Button, Card, EmptyState, ErrorNote, Loading, Notice, PageHeader } from "@/components/ui";
import { api } from "@/lib/api";
import { formatAmount, formatDateTime, humanize } from "@/lib/format";
import { useAnomalies, useSpending } from "@/lib/hooks";
import type { Narrative, SpendingSummary } from "@/lib/types";

function currentMonth(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Africa/Nairobi", year: "numeric", month: "2-digit" }).format(new Date()).slice(0, 7);
}

export default function InsightsPage() {
  const [month, setMonth] = useState(currentMonth);
  const { data, error, isLoading } = useSpending(month);

  return (
    <>
      <PageHeader
        title="Insights"
        description="Where your money went this month."
        action={
          <label className="flex items-center gap-2 text-sm">
            <span className="text-muted">Month</span>
            <input
              type="month"
              value={month}
              max={currentMonth()}
              onChange={(e) => e.target.value && setMonth(e.target.value)}
              className="h-10 rounded-xl border border-border bg-surface px-3"
            />
          </label>
        }
      />
      <div className="space-y-6">
        {isLoading ? <Loading /> : error ? <ErrorNote error={error} /> : data && <Spending summary={data} />}
        <Alerts />
      </div>
    </>
  );
}

function Spending({ summary }: { summary: SpendingSummary }) {
  const categories = [...summary.byCategory].sort((a, b) => Number(b.amount) - Number(a.amount));
  const max = Math.max(...categories.map((c) => Number(c.amount)), 0);

  return (
    <>
      <div className="grid grid-cols-2 gap-4">
        <Card>
          <p className="text-sm text-muted">Money out</p>
          <p className="tabular mt-1 text-2xl font-semibold">{formatAmount(summary.totalOut, summary.currency)}</p>
        </Card>
        <Card>
          <p className="text-sm text-muted">Money in</p>
          <p className="tabular mt-1 text-2xl font-semibold">{formatAmount(summary.totalIn, summary.currency)}</p>
        </Card>
      </div>

      <Card>
        <h2 className="mb-4 font-semibold">Spending by category</h2>
        {categories.length === 0 ? (
          <EmptyState title="No spending recorded for this month" />
        ) : (
          // Ranked bars, one hue: the names and figures beside each bar double as the table view.
          <ul className="space-y-3.5">
            {categories.map((c) => {
              const share = max > 0 ? (Number(c.amount) / max) * 100 : 0;
              return (
                <li key={c.category} className="group" title={`${humanize(c.category)}: ${formatAmount(c.amount, summary.currency)} across ${c.count} transfer${c.count === 1 ? "" : "s"}`}>
                  <div className="mb-1.5 flex items-baseline justify-between gap-3 text-sm">
                    <span className="font-medium">{humanize(c.category)}</span>
                    <span className="tabular">
                      <span className="font-semibold">{formatAmount(c.amount, summary.currency)}</span>
                      <span className="ml-2 text-xs text-muted">{c.count} transfer{c.count === 1 ? "" : "s"}</span>
                    </span>
                  </div>
                  <div className="h-2.5">
                    <div
                      className="h-full rounded-r-[4px] bg-brand transition-opacity group-hover:opacity-80"
                      style={{ width: `${Math.max(share, 1)}%` }}
                    />
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Card>

      {categories.length > 0 && <AiSummary month={summary.month} />}
    </>
  );
}

/** The narrative is written by an LLM from the numbers above; it can be slow or unavailable. */
function AiSummary({ month }: { month: string }) {
  const [text, setText] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    setBusy(true);
    setError(null);
    try {
      setText((await api<Narrative>(`/insights/me/narrative?month=${month}`)).narrative);
    } catch (e) {
      setError(e instanceof Error ? new Error("The summary isn't available right now. Your figures above are unaffected.") : e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="font-semibold">Money coach</h2>
          <p className="text-sm text-muted">A short written summary of this month, with one tip.</p>
        </div>
        {!text && <Button variant="secondary" onClick={load} busy={busy}>Summarise my month</Button>}
      </div>
      <ErrorNote error={error} />
      {text && <p className="whitespace-pre-line text-sm leading-relaxed">{text}</p>}
    </Card>
  );
}

function Alerts() {
  const { data } = useAnomalies();
  if (!data?.length) return null;
  return (
    <Card>
      <h2 className="mb-3 font-semibold">Unusual activity</h2>
      <div className="space-y-3">
        {data.slice(0, 5).map((a) => (
          <Notice key={a.id} tone="warning">
            <p className="font-medium">{humanize(a.kind)} · {formatDateTime(a.createdAt)}</p>
            <p className="mt-1">{a.details}</p>
          </Notice>
        ))}
        <p className="text-xs text-muted">If you don&apos;t recognise a transaction, change your password and contact support.</p>
      </div>
    </Card>
  );
}
