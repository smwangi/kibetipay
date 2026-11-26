"use client";

import useSWR, { type SWRConfiguration } from "swr";
import useSWRInfinite from "swr/infinite";
import { ApiError, fetcher } from "./api";
import type {
  AnomalyAlert,
  CollectionView,
  LoanDetail,
  LoanOffer,
  LoanProduct,
  LoanSummary,
  Notification,
  Page,
  SpendingSummary,
  StatementLine,
  Transfer,
  Wallet,
  WalletSummary,
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
/** Inclusive dates (yyyy-MM-dd, Nairobi time). Omit either end for an open range. */
export type DateRange = { from?: string; to?: string };

/**
 * A newest-first history the user can page through with "Load more". Each page asks for the
 * rows strictly older than the previous page's last row (keyset cursor), so nothing is skipped
 * or repeated when new rows arrive.
 */
export function usePagedHistory<T>(path: string, range: DateRange = {}, pageSize = 25) {
  const result = useSWRInfinite<Page<T>, ApiError>(
    (index, previous: Page<T> | null) => {
      if (previous && !previous.nextCursor) return null;
      const query = new URLSearchParams({ limit: String(pageSize) });
      if (range.from) query.set("from", range.from);
      if (range.to) query.set("to", range.to);
      if (previous?.nextCursor) query.set("cursor", previous.nextCursor);
      return `${path}?${query}`;
    },
    fetcher<Page<T>>,
    { revalidateFirstPage: true, revalidateOnFocus: true },
  );
  const pages = result.data ?? [];
  const last = pages.at(-1);
  return {
    items: pages.flatMap((p) => p.items),
    error: result.error,
    isLoading: result.isLoading,
    isLoadingMore: result.isValidating && result.size > pages.length,
    hasMore: Boolean(last?.nextCursor),
    loadMore: () => result.setSize(result.size + 1),
  };
}

export const useStatementHistory = (range?: DateRange) => usePagedHistory<StatementLine>("/wallets/me/statement", range);
export const useTransferHistory = (range?: DateRange) => usePagedHistory<Transfer>("/transfers", range);
export const useTopUpHistory = (range?: DateRange) => usePagedHistory<CollectionView>("/mobile-money/collections", range);

/** Just the newest few rows, for summaries such as the home dashboard. */
export const useTransfers = (limit = 20) => useApi<Page<Transfer>>(`/transfers?limit=${limit}`);
export const useTopUps = (limit = 5) => useApi<Page<CollectionView>>(`/mobile-money/collections?limit=${limit}`);
export const useWalletSummary = (days = 30) => useApi<WalletSummary>(`/wallets/me/summary?days=${days}`);
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
