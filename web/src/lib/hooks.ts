"use client";

import useSWR, { type SWRConfiguration } from "swr";
import { ApiError, fetcher } from "./api";
import type {
  AnomalyAlert,
  LoanDetail,
  LoanOffer,
  LoanProduct,
  LoanSummary,
  Notification,
  SpendingSummary,
  StatementLine,
  Transfer,
  Wallet,
} from "./types";

function useApi<T>(path: string | null, config?: SWRConfiguration<T, ApiError>) {
  return useSWR<T, ApiError>(path, fetcher<T>, { revalidateOnFocus: true, ...config });
}

/** The signed-in username (from the web session, not the backend). */
export function useMe() {
  return useSWR<{ username: string }>("/api/session", (url: string) =>
    fetch(url).then((r) => (r.ok ? r.json() : Promise.reject(new ApiError(r.status, "Not signed in")))),
  );
}

/** `error.status === 404` means the user has not opened a wallet yet. */
export const useWallet = () => useApi<Wallet>("/wallets/me", { shouldRetryOnError: false });
/** The platform operating currency, as shown on the wallet. Some API amounts omit the currency. */
export function useCurrency(): string | undefined {
  return useWallet().data?.balance.currency;
}
export const useStatement = (limit = 50) => useApi<StatementLine[]>(`/wallets/me/statement?limit=${limit}`);
export const useTransfers = (limit = 20) => useApi<Transfer[]>(`/transfers?limit=${limit}`);
export const useTransfer = (id: string | null, refreshInterval?: number | ((latest?: Transfer) => number)) =>
  useApi<Transfer>(id ? `/transfers/${id}` : null, { refreshInterval });
export const useNotifications = () => useApi<Notification[]>("/notifications", { refreshInterval: 30_000 });
export const useLoans = () => useApi<LoanSummary[]>("/loans");
export const useLoan = (id: string) => useApi<LoanDetail>(`/loans/${id}`);
export const useLoanOffer = (id: string | null) =>
  useApi<LoanOffer>(id ? `/loans/${id}/offer` : null, { shouldRetryOnError: false });
export const useLoanProducts = () => useApi<LoanProduct[]>("/loans/products", { revalidateOnFocus: false });
export const useSpending = (month?: string) =>
  useApi<SpendingSummary>(`/insights/me${month ? `?month=${month}` : ""}`);
export const useAnomalies = () => useApi<AnomalyAlert[]>("/anomalies/me");
