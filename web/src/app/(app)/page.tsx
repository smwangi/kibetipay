"use client";

import Link from "next/link";
import { useState } from "react";
import { ActivityIcon, LoanIcon, SendIcon, TopUpIcon } from "@/components/icons";
import { TransferRow } from "@/components/transfers";
import { Button, Card, EmptyState, ErrorNote, Loading, Notice, StatusBadge } from "@/components/ui";
import { api } from "@/lib/api";
import { formatAmount, formatDate, formatMoney } from "@/lib/format";
import { useCurrency, useLoans, useMe, useNotifications, useTransfers, useWallet } from "@/lib/hooks";
import type { Wallet } from "@/lib/types";

export default function HomePage() {
  const { data: me } = useMe();
  return (
    <>
      <h1 className="mb-6 text-2xl font-semibold tracking-tight">
        {me ? `Habari, ${me.username}` : "Welcome"}
      </h1>
      <div className="space-y-6">
        <BalanceCard />
        <LoanBanner />
        <RecentTransfers me={me?.username} />
        <LatestNotifications />
      </div>
    </>
  );
}

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
    <section className="overflow-hidden rounded-2xl bg-hero p-6 text-on-hero">
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

function LoanBanner() {
  const { data: loans } = useLoans();
  const currency = useCurrency();
  const offered = loans?.find((l) => l.status === "OFFERED");
  const active = loans?.find((l) => l.status === "ACTIVE");
  if (offered) {
    return (
      <Notice tone="info">
        You have a loan offer of <strong>{formatAmount(offered.principal, currency)}</strong>.{" "}
        <Link href={`/loans/${offered.id}`} className="font-semibold underline">Review the offer</Link>
      </Notice>
    );
  }
  if (active && active.nextDueDate) {
    return (
      <Notice tone={active.daysPastDue > 0 ? "warning" : "info"}>
        {active.daysPastDue > 0
          ? `Your loan repayment is ${active.daysPastDue} day${active.daysPastDue === 1 ? "" : "s"} late. `
          : `Next loan repayment due ${formatDate(active.nextDueDate)}. `}
        <Link href={`/loans/${active.id}`} className="font-semibold underline">Repay now</Link>
      </Notice>
    );
  }
  return null;
}

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
      ) : !data?.length ? (
        <EmptyState title="No transfers yet">
          <Link href="/send" className="font-medium text-brand">Send money</Link> to someone to get started.
        </EmptyState>
      ) : (
        <ul className="divide-y divide-border">
          {data.map((t) => <TransferRow key={t.id} transfer={t} me={me} />)}
        </ul>
      )}
    </Card>
  );
}

function LatestNotifications() {
  const { data } = useNotifications();
  const unread = data?.filter((n) => !n.readAt).slice(0, 3) ?? [];
  if (!unread.length) return null;
  return (
    <Card>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="font-semibold">New for you</h2>
        <Link href="/notifications" className="text-sm font-medium text-brand">All notifications</Link>
      </div>
      <ul className="divide-y divide-border text-sm">
        {unread.map((n) => <li key={n.id} className="py-2.5">{n.message}</li>)}
      </ul>
    </Card>
  );
}
