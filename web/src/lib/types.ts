/** Response shapes of the KibetiPay customer API (Spring Boot, see api.http in the repo root). */

export type Money = { minor: number; currency: string };

export type Wallet = {
  id: string;
  ownerId: string;
  balance: Money;
  status: "ACTIVE" | "FROZEN";
  createdAt: string;
};

/** A newest-first page; pass `nextCursor` back as `cursor` for the next page (null on the last). */
export type Page<T> = { items: T[]; nextCursor: string | null };

export type StatementLine = {
  entryId: string;
  journalId: string;
  reference: string;
  /** Journal kind: DEPOSIT, TRANSFER, LOAN_DISBURSEMENT, LOAN_REPAYMENT, COMMISSION, ... */
  kind: string;
  direction: "DEBIT" | "CREDIT";
  amount: Money;
  balanceAfter: Money;
  /** The other person in a transfer; null for deposits, loans and fees. */
  counterparty: string | null;
  at: string;
};

export type WalletSummary = {
  from: string;
  to: string;
  openingBalance: Money;
  closingBalance: Money;
  totalIn: Money;
  totalOut: Money;
  byKind: { kind: string; in: Money; out: Money; count: number }[];
  daily: { date: string; in: Money; out: Money; closingBalance: Money }[];
};

export type PostingReceipt = { journalId: string; reference: string; fromBalanceAfter: Money; replayed: boolean };

export type TransferStatus = "PENDING_RISK" | "IN_REVIEW" | "COMPLETED" | "REJECTED" | "FAILED";

export type Transfer = {
  id: string;
  txnRef: string;
  initiatorId: string;
  recipientId: string;
  /** Major units as a decimal string, e.g. "1000.00". */
  amount: string;
  currency: string;
  note: string | null;
  status: TransferStatus;
  /** Plain-language explanation of the status; null once completed. */
  statusMessage: string | null;
  createdAt: string;
  updatedAt: string;
};

export type TransferAccepted = { transferId: string; txnRef: string; replayed: boolean };

export type CollectionStatus = "SUBMITTING" | "PENDING" | "UNKNOWN" | "REJECTED" | "COMPLETED" | "FAILED";

export type Collection = {
  id: string;
  status: CollectionStatus;
  provider: string;
  providerRequestId: string | null;
  receipt: string | null;
  journalId: string | null;
  replayed: boolean;
};

/** A top-up in the customer's history. `msisdn` is masked, e.g. 2547••••5678. */
export type CollectionView = {
  id: string;
  status: CollectionStatus;
  amount: Money;
  msisdn: string;
  provider: string;
  receipt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type LoanStatus =
  | "OFFERED"
  | "APPLIED"
  | "IN_REVIEW"
  | "DECLINED"
  | "ACTIVE"
  | "REPAID"
  | "DEFAULTED"
  | (string & {});

export type LoanProduct = {
  code: string;
  name: string;
  minPrincipalMinor: number;
  maxPrincipalMinor: number;
  firstLoanMaxMinor: number;
  interestBps: number;
  feeBps: number;
  tenorDays: number;
  installments: number;
  penaltyBpsPerDay: number;
  active: boolean;
};

export type LoanSummary = {
  id: string;
  product: string;
  principal: string;
  totalDue: string;
  outstanding: string;
  status: LoanStatus;
  creditScore: number | null;
  daysPastDue: number;
  nextDueDate: string | null;
  appliedAt: string;
};

export type Installment = { seq: number; dueDate: string; due: string; paid: string; penalty: string; status: string };

export type LoanDetail = {
  summary: LoanSummary;
  borrowerId: string;
  agentUserId: string;
  decisionReasons: string | null;
  schedule: Installment[];
};

export type LoanOffer = {
  loanId: string;
  status: LoanStatus;
  productCode: string;
  currency: string;
  principal: string;
  interest: string;
  fee: string;
  total: string;
  interestBps: number;
  feeBps: number;
  tenorDays: number;
  penaltyBpsPerDay: number;
  schedule: { sequence: number; daysAfterDisbursement: number; principal: string; interest: string; fee: string; total: string }[];
  decision: string | null;
  decidedAt: string | null;
};

export type OfferResponse = { loanId: string; decision: string; decidedAt: string; replayed: boolean };

export type RepaymentReceipt = {
  repaymentId: string;
  loanId: string;
  paid: string;
  penalty: string;
  fee: string;
  interest: string;
  principal: string;
  outstanding: string;
  loanClosed: boolean;
  replayed: boolean;
};

export type Notification = { id: string; kind: string; message: string; createdAt: string; readAt: string | null };

export type SpendingSummary = {
  ownerId: string;
  month: string;
  currency: string;
  totalOut: string;
  totalIn: string;
  byCategory: { category: string; amount: string; count: number }[];
};

export type Narrative = { summary: SpendingSummary; narrative: string };

export type AnomalyAlert = {
  id: string;
  ownerId: string;
  transferId: string;
  kind: string;
  zScore: number;
  details: string;
  acknowledged: boolean;
  createdAt: string;
};
