import type { Money } from "./types";

const LOCALE = "en-KE";

function currencyFormatter(currency: string) {
  return new Intl.NumberFormat(LOCALE, { style: "currency", currency, currencyDisplay: "code" });
}

/** Minor units -> "KES 1,000.00", using the currency's own number of decimals. */
export function formatMoney(money: Money): string {
  const fmt = currencyFormatter(money.currency);
  const digits = fmt.resolvedOptions().maximumFractionDigits ?? 2;
  return fmt.format(money.minor / 10 ** digits);
}

/** The API's decimal strings ("1000.00") -> "KES 1,000.00" (or "1,000.00" while the currency is unknown). */
export function formatAmount(amount: string | number, currency: string | undefined): string {
  if (!currency) {
    return Number(amount).toLocaleString(LOCALE, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  return currencyFormatter(currency).format(Number(amount));
}

export function formatDate(iso: string): string {
  return new Intl.DateTimeFormat(LOCALE, { day: "numeric", month: "short", year: "numeric" }).format(new Date(iso));
}

export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat(LOCALE, {
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(iso));
}

export function formatPercentBps(bps: number): string {
  return `${(bps / 100).toLocaleString(LOCALE, { maximumFractionDigits: 2 })}%`;
}

/** "PENDING_RISK" -> "Pending risk" */
export function humanize(value: string): string {
  const text = value.replace(/_/g, " ").toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Customer-friendly labels for ledger references such as "deposit:alice:key". */
export function describeReference(reference: string): string {
  if (reference.startsWith("seed:")) return "Opening balance";
  if (reference.startsWith("deposit:")) return "Deposit";
  if (reference.startsWith("mobilemoney:collection:")) return "M-Pesa top-up";
  if (reference.startsWith("mobilemoney:suspense:")) return "Payment matched by support";
  if (reference.startsWith("transfer:")) return "Transfer";
  if (reference.startsWith("loan:disburse:")) return "Loan disbursement";
  if (reference.startsWith("loan:repay:")) return "Loan repayment";
  if (reference.startsWith("loan:commission:")) return "Agent commission";
  return humanize(reference.split(":")[0] ?? reference);
}

/** "Transfer to bob" / "Transfer from bob" when the statement names the other person. */
export function describeStatementLine(line: {
  reference: string;
  kind: string;
  direction: "DEBIT" | "CREDIT";
  counterparty: string | null;
}): string {
  if (line.counterparty && line.kind === "TRANSFER") {
    return `${line.direction === "DEBIT" ? "Transfer to" : "Transfer from"} ${line.counterparty}`;
  }
  if (line.counterparty && line.kind === "REVERSAL") {
    return `Reversal ${line.direction === "DEBIT" ? "to" : "from"} ${line.counterparty}`;
  }
  return describeReference(line.reference);
}

const KIND_LABELS: Record<string, string> = {
  DEPOSIT: "Top-ups & deposits",
  TRANSFER: "Transfers",
  REVERSAL: "Reversals",
  LOAN_DISBURSEMENT: "Loans received",
  LOAN_REPAYMENT: "Loan repayments",
  COMMISSION: "Agent commission",
  SUSPENSE_ALLOCATION: "Payments matched by support",
};

/** Ledger journal kinds ("LOAN_REPAYMENT") -> customer wording ("Loan repayments"). */
export function describeKind(kind: string): string {
  return KIND_LABELS[kind] ?? humanize(kind);
}

/** Minor units -> compact axis label without currency: 0, 950, 12K, 1.2M. */
export function formatCompact(minor: number, currency: string): string {
  const digits = currencyFormatter(currency).resolvedOptions().maximumFractionDigits ?? 2;
  return new Intl.NumberFormat(LOCALE, { notation: "compact", maximumFractionDigits: 1 }).format(minor / 10 ** digits);
}

/** A yyyy-MM-dd date (no time zone shift) -> "26 Nov". */
export function formatDay(isoDate: string): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Intl.DateTimeFormat(LOCALE, { day: "numeric", month: "short", timeZone: "UTC" }).format(Date.UTC(y, m - 1, d));
}

/** Today's date in Nairobi as yyyy-MM-dd, optionally shifted by whole days. */
export function nairobiDate(offsetDays = 0): string {
  const now = new Date(Date.now() + offsetDays * 86_400_000);
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Africa/Nairobi" }).format(now);
}

/** Top-up statuses in the words a customer would use. */
export function describeTopUpStatus(status: string): string {
  switch (status) {
    case "SUBMITTING":
    case "PENDING":
      return "Waiting for approval";
    case "UNKNOWN":
      return "Being checked";
    case "COMPLETED":
      return "Added to wallet";
    case "REJECTED":
      return "Declined by provider";
    case "FAILED":
      return "Not completed";
    default:
      return humanize(status);
  }
}
