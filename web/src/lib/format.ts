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
