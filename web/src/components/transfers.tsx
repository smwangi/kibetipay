"use client";

import { formatAmount, formatDateTime } from "@/lib/format";
import type { Transfer } from "@/lib/types";
import { ArrowDownLeft, ArrowUpRight } from "./icons";
import { cn, StatusBadge } from "./ui";

export const TERMINAL_TRANSFER = new Set(["COMPLETED", "REJECTED", "FAILED"]);

export function TransferRow({ transfer, me }: { transfer: Transfer; me: string | undefined }) {
  const outgoing = transfer.initiatorId === me;
  const counterparty = outgoing ? transfer.recipientId : transfer.initiatorId;
  const settled = transfer.status === "COMPLETED";
  return (
    <li className="flex items-center gap-3 py-3">
      <span
        className={cn(
          "grid size-10 shrink-0 place-items-center rounded-full",
          outgoing ? "bg-surface-2 text-muted" : "bg-success-soft text-success",
        )}
      >
        {outgoing ? <ArrowUpRight /> : <ArrowDownLeft />}
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate font-medium">
          {outgoing ? "To" : "From"} {counterparty}
        </p>
        <p className="truncate text-xs text-muted">
          {transfer.note ? `${transfer.note} · ` : ""}
          {formatDateTime(transfer.createdAt)} · <span className="font-mono">{transfer.txnRef}</span>
        </p>
      </div>
      <div className="text-right">
        <p className={cn("tabular font-semibold", !outgoing && settled && "text-success", !settled && "text-muted")}>
          {outgoing ? "−" : "+"}
          {formatAmount(transfer.amount, transfer.currency)}
        </p>
        {!settled && <StatusBadge status={transfer.status} />}
      </div>
    </li>
  );
}
