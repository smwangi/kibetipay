"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState, type SubmitEvent } from "react";
import { useSWRConfig } from "swr";
import { Button, Card, Dl, ErrorNote, Field, Loading, Notice, PageHeader, StatusBadge } from "@/components/ui";
import { api, newIdempotencyKey } from "@/lib/api";
import { formatAmount, formatDate, formatMoney, formatPercentBps } from "@/lib/format";
import { useCurrency, useLoan, useLoanOffer, useWallet } from "@/lib/hooks";
import type { LoanDetail, OfferResponse, RepaymentReceipt } from "@/lib/types";

export default function LoanPage() {
  const { id } = useParams<{ id: string }>();
  const currency = useCurrency();
  const { data: loan, error, isLoading } = useLoan(id);

  if (isLoading) return <Loading label="Loading loan…" />;
  if (error || !loan) return <ErrorNote error={error ?? new Error("Loan not found")} />;
  const s = loan.summary;

  return (
    <>
      <Link href="/loans" className="mb-3 inline-block text-sm font-medium text-brand">← All loans</Link>
      <PageHeader title={s.product} description={`Applied ${formatDate(s.appliedAt)} through agent ${loan.agentUserId}`} action={<StatusBadge status={s.status} />} />
      <div className="space-y-6">
        {s.status === "OFFERED" && <OfferReview loanId={s.id} />}
        {s.status === "ACTIVE" && <Repay loan={loan} currency={currency} />}
        {s.status === "DECLINED" && loan.decisionReasons && <Notice tone="danger">Not approved: {loan.decisionReasons}</Notice>}
        {(s.status === "APPLIED" || s.status === "IN_REVIEW") && (
          <Notice tone="info">You accepted this offer. We are checking the application; the money goes to your wallet once approved.</Notice>
        )}

        <Card>
          <h2 className="mb-2 font-semibold">Summary</h2>
          <Dl
            items={[
              ["Principal", formatAmount(s.principal, currency)],
              ["Total to repay", formatAmount(s.totalDue, currency)],
              ["Outstanding", formatAmount(s.outstanding, currency)],
              ...(s.nextDueDate ? ([["Next due", formatDate(s.nextDueDate)]] as [string, string][]) : []),
              ...(s.daysPastDue > 0 ? ([["Days late", String(s.daysPastDue)]] as [string, string][]) : []),
            ]}
          />
        </Card>

        {loan.schedule.length > 0 && (
          <Card className="p-0">
            <h2 className="px-5 pt-5 font-semibold">Repayment schedule</h2>
            <div className="overflow-x-auto">
              <table className="mt-2 w-full text-sm">
                <thead className="text-left text-xs uppercase tracking-wide text-muted">
                  <tr className="border-b border-border">
                    <th className="px-5 py-3 font-medium">#</th>
                    <th className="px-5 py-3 font-medium">Due date</th>
                    <th className="px-5 py-3 text-right font-medium">Due</th>
                    <th className="px-5 py-3 text-right font-medium">Paid</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {loan.schedule.map((i) => (
                    <tr key={i.seq}>
                      <td className="px-5 py-3 text-muted">{i.seq}</td>
                      <td className="whitespace-nowrap px-5 py-3">{formatDate(i.dueDate)}</td>
                      <td className="tabular whitespace-nowrap px-5 py-3 text-right">
                        {formatAmount(i.due, currency)}
                        {Number(i.penalty) > 0 && <span className="block text-xs text-warning">+ {formatAmount(i.penalty, currency)} penalty</span>}
                      </td>
                      <td className="tabular whitespace-nowrap px-5 py-3 text-right">{formatAmount(i.paid, currency)}</td>
                      <td className="px-5 py-3"><StatusBadge status={i.status} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        )}
      </div>
    </>
  );
}

function OfferReview({ loanId }: { loanId: string }) {
  const { mutate } = useSWRConfig();
  const { data: offer, error, isLoading } = useLoanOffer(loanId);
  const [busy, setBusy] = useState<"accept" | "decline" | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);

  async function respond(choice: "accept" | "decline") {
    setBusy(choice);
    setActionError(null);
    try {
      await api<OfferResponse>(`/loans/${loanId}/offer/${choice}`, { method: "POST" });
      await mutate((k) => typeof k === "string" && k.startsWith("/loans"));
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(null);
    }
  }

  if (isLoading) return <Card><Loading label="Loading offer…" /></Card>;
  if (error || !offer) return <ErrorNote error={error} />;

  return (
    <Card className="space-y-4 border-brand">
      <div>
        <h2 className="text-lg font-semibold">Review your loan offer</h2>
        <p className="mt-1 text-sm text-muted">Read the terms before you accept. Accepting starts the approval check.</p>
      </div>
      <Dl
        items={[
          ["You receive", formatAmount(offer.principal, offer.currency)],
          [`Interest (${formatPercentBps(offer.interestBps)} flat)`, formatAmount(offer.interest, offer.currency)],
          [`Fee (${formatPercentBps(offer.feeBps)})`, formatAmount(offer.fee, offer.currency)],
          ["Total to repay", <strong key="t">{formatAmount(offer.total, offer.currency)}</strong>],
          ["Term", `${offer.tenorDays} days, ${offer.schedule.length} instalment${offer.schedule.length === 1 ? "" : "s"}`],
          ["Late penalty", `${formatPercentBps(offer.penaltyBpsPerDay)} per day`],
        ]}
      />
      <details className="text-sm">
        <summary className="cursor-pointer font-medium text-brand">Instalment plan</summary>
        <ul className="mt-2 divide-y divide-border">
          {offer.schedule.map((line) => (
            <li key={line.sequence} className="flex justify-between py-2">
              <span className="text-muted">#{line.sequence} · day {line.daysAfterDisbursement}</span>
              <span className="tabular font-medium">{formatAmount(line.total, offer.currency)}</span>
            </li>
          ))}
        </ul>
      </details>
      <ErrorNote error={actionError} />
      <div className="flex gap-3">
        <Button onClick={() => respond("accept")} busy={busy === "accept"} disabled={busy !== null}>Accept offer</Button>
        <Button variant="secondary" onClick={() => respond("decline")} busy={busy === "decline"} disabled={busy !== null}>Decline</Button>
      </div>
    </Card>
  );
}

function Repay({ loan, currency }: { loan: LoanDetail; currency: string | undefined }) {
  const { mutate } = useSWRConfig();
  const { data: wallet } = useWallet();
  const [key, setKey] = useState(newIdempotencyKey);
  const [amountError, setAmountError] = useState<string>();
  const [error, setError] = useState<unknown>(null);
  const [receipt, setReceipt] = useState<RepaymentReceipt | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const amount = String(new FormData(event.currentTarget).get("amount") ?? "").trim();
    if (!/^\d{1,9}(\.\d{1,2})?$/.test(amount) || Number(amount) < 1) {
      setAmountError("Enter an amount of at least 1.00");
      return;
    }
    setAmountError(undefined);
    setBusy(true);
    setError(null);
    try {
      setReceipt(await api<RepaymentReceipt>(`/loans/${loan.summary.id}/repayments`, { method: "POST", idempotencyKey: key, body: { amount: Number(amount) } }));
      setKey(newIdempotencyKey());
      await mutate((k) => typeof k === "string" && (k.startsWith("/loans") || k.startsWith("/wallets")));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="space-y-4">
      <div>
        <h2 className="font-semibold">Make a repayment</h2>
        <p className="mt-1 text-sm text-muted">
          Paid from your wallet{wallet ? ` (balance ${formatMoney(wallet.balance)})` : ""}. Payments clear penalties and fees first, then interest, then principal.
        </p>
      </div>
      {receipt && (
        <Notice tone="success">
          {receipt.loanClosed
            ? "Paid in full. Your loan is closed."
            : `Paid ${formatAmount(receipt.paid, currency)}. Still outstanding: ${formatAmount(receipt.outstanding, currency)}.`}
        </Notice>
      )}
      <form onSubmit={onSubmit} noValidate className="flex flex-wrap items-start gap-3">
        <div className="min-w-48 flex-1">
          <Field id="repay-amount" name="amount" label="Amount" inputMode="decimal" placeholder={loan.summary.outstanding} error={amountError} />
        </div>
        <Button type="submit" busy={busy} className="mt-7">Repay</Button>
      </form>
      <ErrorNote error={error} />
    </Card>
  );
}
