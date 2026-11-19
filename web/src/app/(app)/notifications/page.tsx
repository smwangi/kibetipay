"use client";

import { Button, Card, cn, EmptyState, ErrorNote, Loading, PageHeader } from "@/components/ui";
import { api } from "@/lib/api";
import { formatDateTime, humanize } from "@/lib/format";
import { useNotifications } from "@/lib/hooks";

export default function NotificationsPage() {
  const { data, error, isLoading, mutate } = useNotifications();
  const unread = data?.filter((n) => !n.readAt) ?? [];

  async function markRead(ids: string[]) {
    const now = new Date().toISOString();
    // Optimistic: show as read immediately, then confirm with the server.
    await mutate(
      async (current) => {
        await Promise.all(ids.map((id) => api(`/notifications/${id}/read`, { method: "POST" })));
        return current?.map((n) => (ids.includes(n.id) ? { ...n, readAt: n.readAt ?? now } : n));
      },
      { optimisticData: (current) => current?.map((n) => (ids.includes(n.id) ? { ...n, readAt: n.readAt ?? now } : n)) ?? [], rollbackOnError: true },
    );
  }

  return (
    <>
      <PageHeader
        title="Notifications"
        description="Updates about your loans and account."
        action={unread.length > 0 && <Button variant="secondary" onClick={() => markRead(unread.map((n) => n.id))}>Mark all as read</Button>}
      />
      <Card>
        {isLoading ? (
          <Loading />
        ) : error ? (
          <ErrorNote error={error} />
        ) : !data?.length ? (
          <EmptyState title="You're all caught up" />
        ) : (
          <ul className="divide-y divide-border">
            {data.map((n) => (
              <li key={n.id} className="flex items-start gap-3 py-3">
                <span aria-hidden className={cn("mt-1.5 size-2 shrink-0 rounded-full", n.readAt ? "bg-transparent" : "bg-accent")} />
                <div className="min-w-0 flex-1">
                  <p className={cn("text-sm", !n.readAt && "font-medium")}>{n.message}</p>
                  <p className="mt-0.5 text-xs text-muted">
                    {humanize(n.kind)} · {formatDateTime(n.createdAt)}
                    {!n.readAt && <span className="sr-only"> · unread</span>}
                  </p>
                </div>
                {!n.readAt && (
                  <button onClick={() => markRead([n.id])} className="shrink-0 text-xs font-medium text-brand hover:underline">
                    Mark read
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}
