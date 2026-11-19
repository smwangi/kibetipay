"use client";

import Link from "next/link";
import { Card, EmptyState, ErrorNote, Loading, PageHeader, StatusBadge } from "@/components/ui";
import { formatAmount, formatDate, formatMoney, formatPercentBps } from "@/lib/format";
import { useCurrency, useLoanProducts, useLoans } from "@/lib/hooks";

export default function LoansPage() {
  const currency = useCurrency();
  const { data: loans, error, isLoading } = useLoans();

  return (
    <>
      <PageHeader title="Loans" description="Loans are arranged through your KibetiPay agent. Offers they make for you appear here." />
      <div className="space-y-6">
        <Card>
          <h2 className="mb-2 font-semibold">Your loans</h2>
          {isLoading ? (
            <Loading />
          ) : error ? (
            <ErrorNote error={error} />
          ) : !loans?.length ? (
            <EmptyState title="No loans yet">Ask your agent about a loan. Their offer will show up here for you to review.</EmptyState>
          ) : (
            <ul className="divide-y divide-border">
              {loans.map((loan) => (
                <li key={loan.id}>
                  <Link href={`/loans/${loan.id}`} className="-mx-2 flex items-center gap-4 rounded-xl px-2 py-3 hover:bg-surface-2">
                    <div className="min-w-0 flex-1">
                      <p className="font-medium">{loan.product}</p>
                      <p className="text-xs text-muted">
                        Applied {formatDate(loan.appliedAt)}
                        {loan.nextDueDate && loan.status === "ACTIVE" ? ` · next due ${formatDate(loan.nextDueDate)}` : ""}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="tabular font-semibold">{formatAmount(loan.status === "ACTIVE" ? loan.outstanding : loan.principal, currency)}</p>
                      <p className="text-xs text-muted">{loan.status === "ACTIVE" ? "outstanding" : "principal"}</p>
                    </div>
                    <StatusBadge status={loan.status} label={loan.status === "OFFERED" ? "Offer to review" : undefined} />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Products currency={currency} />
      </div>
    </>
  );
}

function Products({ currency }: { currency: string | undefined }) {
  const { data } = useLoanProducts();
  if (!data?.length || !currency) return null;
  return (
    <section>
      <h2 className="mb-3 font-semibold">Loan products</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        {data.filter((p) => p.active).map((p) => (
          <Card key={p.code}>
            <p className="font-medium">{p.name}</p>
            <p className="mt-1 text-sm text-muted">
              {formatMoney({ minor: p.minPrincipalMinor, currency })} – {formatMoney({ minor: p.maxPrincipalMinor, currency })}
            </p>
            <ul className="mt-3 space-y-1 text-sm">
              <li>{p.tenorDays} days, {p.installments} instalment{p.installments === 1 ? "" : "s"}</li>
              <li>Interest {formatPercentBps(p.interestBps)} flat, fee {formatPercentBps(p.feeBps)}</li>
              <li>First loan up to {formatMoney({ minor: p.firstLoanMaxMinor, currency })}</li>
            </ul>
          </Card>
        ))}
      </div>
    </section>
  );
}
