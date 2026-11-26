"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import { AreaChart, BarList, Meter, StatTile } from "@/components/charts";
import { ActivityIcon, LoanIcon, SendIcon, TopUpIcon } from "@/components/icons";
import { TransferRow } from "@/components/transfers";
import { Button, Card, cn, EmptyState, ErrorNote, Loading, StatusBadge } from "@/components/ui";
import { api } from "@/lib/api";
import {
  describeKind,
  describeTopUpStatus,
  formatAmount,
  formatCompact,
  formatDate,
  formatDateTime,
  formatDay,
  formatMoney,
  humanize,
} from "@/lib/format";
import {
  useAnomalies,
  useCurrency,
  useLoans,
  useMe,
  useNotifications,
  useSpending,
  useTopUps,
  useTransfers,
  useWallet,
  useWalletSummary,
} from "@/lib/hooks";
import type { LoanSummary, Money, Wallet, WalletSummary } from "@/lib/types";

/**
 * Landing dashboard: balance and quick actions, what needs attention, money in/out over a chosen
 * period (all from the ledger, so top-ups and loans count too), spending, the active loan and
 * the latest activity. Every figure links to the page that holds the detail.
 */
export default function DashboardPage() {
  const { data: me } = useMe();
  const { error: walletError } = useWallet();
  const noWallet = walletError?.status === 404;
  return (
    <>
      <h1 className="mb-6 text-2xl font-semibold tracking-tight">{me ? `Habari, ${me.username}` : "Welcome"}</h1>
      <div className="space-y-6">
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="lg:col-span-2">
            <BalanceCard />
          </div>
          {!noWallet && <NeedsAttention />}
        </div>
        {!noWallet && (
          <>
            <MoneyOverview />
            <div className="grid gap-6 lg:grid-cols-2">
              <SpendingThisMonth />
              <LoanCard />
            </div>
            <div className="grid gap-6 lg:grid-cols-2">
              <RecentTransfers me={me?.username} />
              <RecentTopUps />
            </div>
          </>
        )}
      </div>
    </>
  );
}

// ---------- Balance + quick actions ----------

function BalanceCard() {
  const { data: wallet, error, isLoading, mutate } = useWallet();
  const [opening, setOpening] = useState(false);
  const [openError, setOpenError] = useState<unknown>(null);

  if (isLoading) return <Card><Loading label="Loading your balance…" /></Card>;

  if (error?.status === 404) {
    async function open() {
      setOpening(true);
      setOpenError(null);
      try {
        await mutate(api<Wallet>("/wallets", { method: "POST" }), { revalidate: false });
      } catch (e) {
        setOpenError(e);
      } finally {
        setOpening(false);
      }
    }
    return (
      <Card className="space-y-4">
        <div>
          <h2 className="text-lg font-semibold">Open your wallet</h2>
          <p className="mt-1 text-sm text-muted">You need a KibetiPay wallet to send, receive and borrow money.</p>
        </div>
        <ErrorNote error={openError} />
        <Button onClick={open} busy={opening}>Open my wallet</Button>
      </Card>
    );
  }
  if (error || !wallet) return <ErrorNote error={error} />;

  return (
    <section className="h-full overflow-hidden rounded-2xl bg-hero p-6 text-on-hero">
      <div className="flex items-start justify-between">
        <p className="text-sm opacity-80">Available balance</p>
        {wallet.status !== "ACTIVE" && <StatusBadge status="FROZEN" label="Frozen" />}
      </div>
      <p className="tabular mt-2 text-4xl font-semibold tracking-tight">{formatMoney(wallet.balance)}</p>
      <p className="mt-1 text-xs opacity-70">Wallet opened {formatDate(wallet.createdAt)}</p>
      <div className="mt-6 grid grid-cols-4 gap-2">
        {[
          { href: "/send", label: "Send", icon: SendIcon },
          { href: "/top-up", label: "Top up", icon: TopUpIcon },
          { href: "/activity", label: "Activity", icon: ActivityIcon },
          { href: "/loans", label: "Loans", icon: LoanIcon },
        ].map(({ href, label, icon: Icon }) => (
          <Link
            key={href}
            href={href}
            className="flex flex-col items-center gap-1.5 rounded-xl bg-white/12 py-3 text-xs font-medium transition-colors hover:bg-white/20"
          >
            <Icon />
            {label}
          </Link>
        ))}
      </div>
    </section>
  );
}

// ---------- Needs attention ----------

type Attention = { key: string; tone: "info" | "warning" | "danger"; text: ReactNode; href: string; action: string };

function NeedsAttention() {
  const { data: wallet } = useWallet();
  const { data: loans } = useLoans();
  const { data: transfers } = useTransfers(20);
  const { data: topUps } = useTopUps(10);
  const { data: notifications } = useNotifications();
  const { data: anomalies } = useAnomalies();
  const currency = useCurrency();
  // Fixed at mount: "this week" doesn't need to move while the page is open.
  const [weekAgo] = useState(() => Date.now() - 7 * 86_400_000);

  const items: Attention[] = [];
  if (wallet && wallet.status !== "ACTIVE") {
    items.push({ key: "frozen", tone: "danger", text: "Your wallet is on hold. You can't send money until support lifts it.", href: "/assistant", action: "Get help" });
  }
  const late = loans?.find((l) => l.status === "ACTIVE" && l.daysPastDue > 0);
  const due = loans?.find((l) => l.status === "ACTIVE" && l.nextDueDate && l.daysPastDue === 0);
  const offer = loans?.find((l) => l.status === "OFFERED");
  if (late) {
    items.push({ key: "late", tone: "danger", text: `Loan repayment is ${late.daysPastDue} day${late.daysPastDue === 1 ? "" : "s"} late.`, href: `/loans/${late.id}`, action: "Repay" });
  } else if (due?.nextDueDate) {
    items.push({ key: "due", tone: "info", text: `Next loan repayment due ${formatDate(due.nextDueDate)}.`, href: `/loans/${due.id}`, action: "Repay" });
  }
  if (offer) {
    items.push({ key: "offer", tone: "info", text: <>Loan offer of <strong>{formatAmount(offer.principal, currency)}</strong> waiting.</>, href: `/loans/${offer.id}`, action: "Review" });
  }
  const held = transfers?.items.filter((t) => t.status === "IN_REVIEW") ?? [];
  if (held.length) {
    items.push({ key: "review", tone: "warning", text: `${held.length} transfer${held.length === 1 ? " is" : "s are"} being checked. The money hasn't left your wallet.`, href: "/activity", action: "View" });
  }
  const waiting = topUps?.items.filter((c) => ["SUBMITTING", "PENDING", "UNKNOWN"].includes(c.status)) ?? [];
  if (waiting.length) {
    items.push({ key: "topup-wait", tone: "info", text: `${waiting.length} M-Pesa top-up${waiting.length === 1 ? "" : "s"} not settled yet.`, href: "/activity?tab=topups", action: "View" });
  }
  const failed = topUps?.items.filter((c) => ["FAILED", "REJECTED"].includes(c.status) && Date.parse(c.updatedAt) > weekAgo) ?? [];
  if (failed.length) {
    items.push({ key: "topup-failed", tone: "warning", text: `${failed.length} top-up${failed.length === 1 ? "" : "s"} didn't go through this week. You weren't charged.`, href: "/activity?tab=topups", action: "View" });
  }
  const alerts = anomalies?.filter((a) => !a.acknowledged) ?? [];
  if (alerts.length) {
    items.push({ key: "anomaly", tone: "warning", text: `We noticed ${alerts.length === 1 ? "an unusual transaction" : `${alerts.length} unusual transactions`}.`, href: "/insights", action: "Check" });
  }
  const unread = notifications?.filter((n) => !n.readAt) ?? [];

  return (
    <Card className="flex flex-col">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="font-semibold">Needs your attention</h2>
        {unread.length > 0 && (
          <Link href="/notifications" className="text-sm font-medium text-brand">{unread.length} unread</Link>
        )}
      </div>
      {items.length === 0 ? (
        <p className="text-sm text-muted">You&apos;re all caught up.</p>
      ) : (
        <ul className="space-y-2">
          {items.map((item) => (
            <li key={item.key} className="flex items-start gap-3 rounded-xl bg-surface-2 p-3 text-sm">
              <span
                aria-hidden
                className={cn("mt-1.5 size-2 shrink-0 rounded-full", { info: "bg-info", warning: "bg-warning", danger: "bg-danger" }[item.tone])}
              />
              <span className="flex-1">{item.text}</span>
              <Link href={item.href} className="shrink-0 font-medium text-brand">{item.action}</Link>
            </li>
          ))}
        </ul>
      )}
      {unread.length > 0 && (
        <ul className="mt-4 space-y-1.5 border-t border-border pt-3 text-sm">
          {unread.slice(0, 2).map((n) => (
            <li key={n.id} className="text-muted">{n.message}</li>
          ))}
        </ul>
      )}
    </Card>
  );
}

// ---------- Money in / out over a period (from the ledger) ----------

const PERIODS = [7, 30, 90] as const;

function MoneyOverview() {
  const [days, setDays] = useState<(typeof PERIODS)[number]>(30);
  const { data, error, isLoading } = useWalletSummary(days);
  const [showTable, setShowTable] = useState(false);

  return (
    <section aria-labelledby="money-heading" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="money-heading" className="text-lg font-semibold">Your money</h2>
        <div role="radiogroup" aria-label="Period" className="inline-flex rounded-xl border border-border bg-surface p-1 text-sm">
          {PERIODS.map((p) => (
            <button
              key={p}
              role="radio"
              aria-checked={days === p}
              onClick={() => setDays(p)}
              className={cn("rounded-lg px-3 py-1.5 font-medium", days === p ? "bg-brand text-on-brand" : "text-muted hover:text-text")}
            >
              {p} days
            </button>
          ))}
        </div>
      </div>

      {error ? (
        <ErrorNote error={error} />
      ) : !data ? (
        isLoading && <Card><Loading /></Card>
      ) : (
        <div className={cn("space-y-4 transition-opacity", isLoading && "opacity-60")}>
          <Kpis summary={data} days={days} />
          <div className="grid gap-6 lg:grid-cols-3">
            <Card className="lg:col-span-2">
              <div className="mb-3 flex items-baseline justify-between gap-3">
                <div>
                  <h3 className="font-semibold">Balance over the last {days} days</h3>
                  <p className="text-xs text-muted">End-of-day balance, {formatDay(data.from)} to {formatDay(data.to)}</p>
                </div>
                <button onClick={() => setShowTable((s) => !s)} className="text-sm font-medium text-brand" aria-expanded={showTable}>
                  {showTable ? "Show chart" : "Show table"}
                </button>
              </div>
              {showTable ? <DailyTable summary={data} /> : <BalanceTrend summary={data} />}
            </Card>
            <Card>
              <h3 className="mb-4 font-semibold">Where it came from and went</h3>
              <FlowBars summary={data} />
            </Card>
          </div>
        </div>
      )}
    </section>
  );
}

function Kpis({ summary, days }: { summary: WalletSummary; days: number }) {
  const net = summary.closingBalance.minor - summary.openingBalance.minor;
  const netMoney: Money = { minor: Math.abs(net), currency: summary.closingBalance.currency };
  const movements = summary.byKind.reduce((n, k) => n + k.count, 0);
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <StatTile label={`Money in · ${days} days`} value={formatMoney(summary.totalIn)} />
      <StatTile label={`Money out · ${days} days`} value={formatMoney(summary.totalOut)} />
      <StatTile
        label="Net change"
        value={`${net > 0 ? "+" : net < 0 ? "−" : ""}${formatMoney(netMoney)}`}
        detail={`from ${formatMoney(summary.openingBalance)} on ${formatDay(summary.from)}`}
        tone={net > 0 ? "positive" : net < 0 ? "negative" : "neutral"}
      />
      <StatTile label="Movements" value={movements.toLocaleString("en-KE")} detail="ledger entries in the period" />
    </div>
  );
}

function BalanceTrend({ summary }: { summary: WalletSummary }) {
  const currency = summary.closingBalance.currency;
  const points = summary.daily.map((d) => ({
    label: formatDay(d.date),
    value: d.closingBalance.minor,
    detail:
      d.in.minor || d.out.minor
        ? `In ${formatMoney(d.in)} · Out ${formatMoney(d.out)}`
        : "No movements",
  }));
  return (
    <AreaChart
      points={points}
      ariaLabel={`Balance from ${formatDay(summary.from)} (${formatMoney(summary.openingBalance)}) to ${formatDay(summary.to)} (${formatMoney(summary.closingBalance)}). Use the arrow keys to read each day.`}
      formatValue={(v) => formatMoney({ minor: v, currency })}
      formatTick={(v) => formatCompact(v, currency)}
    />
  );
}

function DailyTable({ summary }: { summary: WalletSummary }) {
  return (
    <div className="max-h-64 overflow-auto">
      <table className="w-full text-sm">
        <thead className="sticky top-0 bg-surface text-left text-xs uppercase tracking-wide text-muted">
          <tr className="border-b border-border">
            <th className="py-2 pr-3 font-medium">Day</th>
            <th className="py-2 pr-3 text-right font-medium">In</th>
            <th className="py-2 pr-3 text-right font-medium">Out</th>
            <th className="py-2 text-right font-medium">Balance</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {[...summary.daily].reverse().map((d) => (
            <tr key={d.date}>
              <td className="py-2 pr-3 text-muted">{formatDay(d.date)}</td>
              <td className="tabular py-2 pr-3 text-right">{d.in.minor ? formatMoney(d.in) : "–"}</td>
              <td className="tabular py-2 pr-3 text-right">{d.out.minor ? formatMoney(d.out) : "–"}</td>
              <td className="tabular py-2 text-right font-medium">{formatMoney(d.closingBalance)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FlowBars({ summary }: { summary: WalletSummary }) {
  const toBars = (pick: (k: WalletSummary["byKind"][number]) => Money) =>
    summary.byKind.map((k) => ({ key: k.kind, label: describeKind(k.kind), value: pick(k).minor, display: formatMoney(pick(k)) }));
  return (
    <div className="space-y-6">
      <div>
        <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted">Money in</p>
        <BarList data={toBars((k) => k.in)} emptyText="Nothing came in during this period." />
      </div>
      <div>
        <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted">Money out</p>
        <BarList data={toBars((k) => k.out)} emptyText="Nothing went out during this period." />
      </div>
    </div>
  );
}

// ---------- Spending by category (insights) ----------

function SpendingThisMonth() {
  const { data, error, isLoading } = useSpending();
  const currency = useCurrency();
  return (
    <Card>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="font-semibold">Spending this month</h2>
        <Link href="/insights" className="text-sm font-medium text-brand">Insights</Link>
      </div>
      {isLoading ? (
        <Loading />
      ) : error || !data ? (
        <p className="text-sm text-muted">Spending insights aren&apos;t available right now.</p>
      ) : (
        <>
          <p className="tabular mb-4 text-sm text-muted">
            <span className="text-lg font-semibold text-text">{formatAmount(data.totalOut, data.currency ?? currency)}</span> sent
            across {data.byCategory.reduce((n, c) => n + c.count, 0)} payments
          </p>
          <BarList
            data={data.byCategory.slice(0, 6).map((c) => ({
              key: c.category,
              label: humanize(c.category),
              value: Number(c.amount),
              display: formatAmount(c.amount, data.currency ?? currency),
              hint: `${c.count} payment${c.count === 1 ? "" : "s"}`,
            }))}
            emptyText="No spending yet this month."
          />
        </>
      )}
    </Card>
  );
}

// ---------- Active loan ----------

function LoanCard() {
  const { data: loans, isLoading } = useLoans();
  const currency = useCurrency();
  const loan: LoanSummary | undefined =
    loans?.find((l) => l.status === "ACTIVE") ?? loans?.find((l) => ["OFFERED", "APPLIED", "IN_REVIEW"].includes(l.status));
  return (
    <Card>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="font-semibold">Your loan</h2>
        <Link href="/loans" className="text-sm font-medium text-brand">Loans</Link>
      </div>
      {isLoading ? (
        <Loading />
      ) : !loan ? (
        <EmptyState title="No active loan">
          <Link href="/loans" className="font-medium text-brand">See what you can borrow</Link>
        </EmptyState>
      ) : loan.status !== "ACTIVE" ? (
        <div className="space-y-2 text-sm">
          <div className="flex items-center gap-2">
            <StatusBadge status={loan.status} />
            <span className="text-muted">{loan.product}</span>
          </div>
          <p>
            <span className="font-semibold">{formatAmount(loan.principal, currency)}</span> applied {formatDate(loan.appliedAt)}.
          </p>
          <Link href={`/loans/${loan.id}`} className="font-medium text-brand">View details</Link>
        </div>
      ) : (
        <LoanProgress loan={loan} currency={currency} />
      )}
    </Card>
  );
}

function LoanProgress({ loan, currency }: { loan: LoanSummary; currency: string | undefined }) {
  const total = Number(loan.totalDue);
  const outstanding = Number(loan.outstanding);
  const repaid = total > 0 ? (total - outstanding) / total : 0;
  const late = loan.daysPastDue > 0;
  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between gap-3">
        <div>
          <p className="text-xs text-muted">Outstanding</p>
          <p className="tabular text-xl font-semibold">{formatAmount(loan.outstanding, currency)}</p>
        </div>
        <p className="text-right text-xs text-muted">
          of {formatAmount(loan.totalDue, currency)} total
          <br />
          {loan.product}
        </p>
      </div>
      <div>
        <Meter value={repaid} label="Share of the loan repaid" tone={late ? "danger" : "brand"} />
        <p className="mt-1.5 text-xs text-muted">{Math.round(repaid * 100)}% repaid</p>
      </div>
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className={cn(late ? "font-medium text-danger" : "text-muted")}>
          {late
            ? `${loan.daysPastDue} day${loan.daysPastDue === 1 ? "" : "s"} late`
            : loan.nextDueDate
              ? `Next payment due ${formatDate(loan.nextDueDate)}`
              : "No payment due"}
        </span>
        <Link href={`/loans/${loan.id}`} className="font-medium text-brand">Repay</Link>
      </div>
    </div>
  );
}

// ---------- Latest activity ----------

function RecentTransfers({ me }: { me: string | undefined }) {
  const { data, error, isLoading } = useTransfers(5);
  return (
    <Card>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="font-semibold">Recent transfers</h2>
        <Link href="/activity" className="text-sm font-medium text-brand">See all</Link>
      </div>
      {isLoading ? (
        <Loading />
      ) : error ? (
        <ErrorNote error={error} />
      ) : !data?.items.length ? (
        <EmptyState title="No transfers yet">
          <Link href="/send" className="font-medium text-brand">Send money</Link> to someone to get started.
        </EmptyState>
      ) : (
        <ul className="divide-y divide-border">
          {data.items.map((t) => <TransferRow key={t.id} transfer={t} me={me} />)}
        </ul>
      )}
    </Card>
  );
}

function RecentTopUps() {
  const { data, error, isLoading } = useTopUps(5);
  return (
    <Card>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="font-semibold">Recent top-ups</h2>
        <Link href="/activity?tab=topups" className="text-sm font-medium text-brand">See all</Link>
      </div>
      {isLoading ? (
        <Loading />
      ) : error ? (
        <ErrorNote error={error} />
      ) : !data?.items.length ? (
        <EmptyState title="No top-ups yet">
          <Link href="/top-up" className="font-medium text-brand">Top up</Link> from M-Pesa.
        </EmptyState>
      ) : (
        <ul className="divide-y divide-border">
          {data.items.map((c) => (
            <li key={c.id} className="flex items-center justify-between gap-3 py-3 text-sm">
              <div className="min-w-0">
                <p className="truncate font-medium">From {c.msisdn}</p>
                <p className="text-xs text-muted">{formatDateTime(c.createdAt)}</p>
              </div>
              <div className="text-right">
                <p className={cn("tabular font-semibold", c.status === "COMPLETED" ? "text-success" : "text-muted")}>
                  {formatMoney(c.amount)}
                </p>
                <StatusBadge status={c.status} label={describeTopUpStatus(c.status)} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
