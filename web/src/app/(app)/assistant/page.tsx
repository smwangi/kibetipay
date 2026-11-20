"use client";

import { useEffect, useRef, useState, type SubmitEvent } from "react";
import { Button, Card, cn, Notice, PageHeader, Spinner } from "@/components/ui";
import { api } from "@/lib/api";

type Message = { role: "user" | "assistant"; text: string; failed?: boolean };

const SUGGESTIONS = [
  "What is my balance?",
  "How much did I spend this month?",
  "I sent money to the wrong person. What do I do?",
  "When is my next loan payment?",
];

export default function AssistantPage() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages, busy]);

  async function ask(question: string) {
    const text = question.trim();
    if (!text || busy) return;
    setMessages((m) => [...m, { role: "user", text }]);
    setDraft("");
    setBusy(true);
    try {
      const { answer } = await api<{ answer: string }>("/assistant/ask", { method: "POST", body: { question: text.slice(0, 1000) } });
      setMessages((m) => [...m, { role: "assistant", text: answer }]);
    } catch {
      setMessages((m) => [...m, { role: "assistant", text: "Sorry, I can't answer right now. Please try again later or contact support.", failed: true }]);
    } finally {
      setBusy(false);
    }
  }

  function onSubmit(event: SubmitEvent) {
    event.preventDefault();
    void ask(draft);
  }

  return (
    <>
      <PageHeader title="Help" description="Ask about fees, limits, disputes, your balance, spending or loans." />
      <Card className="flex min-h-[60vh] flex-col p-0">
        <div className="flex-1 space-y-4 overflow-y-auto p-5" aria-live="polite">
          {messages.length === 0 && (
            <div className="space-y-3">
              <p className="text-sm text-muted">Try one of these:</p>
              <div className="flex flex-wrap gap-2">
                {SUGGESTIONS.map((s) => (
                  <button key={s} onClick={() => ask(s)} className="rounded-full border border-border px-3 py-1.5 text-sm hover:bg-surface-2">
                    {s}
                  </button>
                ))}
              </div>
            </div>
          )}
          {messages.map((m, i) => (
            <div key={i} className={cn("flex", m.role === "user" ? "justify-end" : "justify-start")}>
              <div
                className={cn(
                  "max-w-[85%] whitespace-pre-line rounded-2xl px-4 py-2.5 text-sm leading-relaxed",
                  m.role === "user" ? "bg-brand text-on-brand" : m.failed ? "bg-danger-soft text-danger" : "bg-surface-2",
                )}
              >
                {m.text}
              </div>
            </div>
          ))}
          {busy && (
            <div className="flex items-center gap-2 text-sm text-muted">
              <Spinner /> Thinking…
            </div>
          )}
          <div ref={endRef} />
        </div>
        <form onSubmit={onSubmit} className="flex gap-2 border-t border-border p-3">
          <label htmlFor="question" className="sr-only">Your question</label>
          <input
            id="question"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            maxLength={1000}
            placeholder="Type your question…"
            className="h-11 min-w-0 flex-1 rounded-xl border border-border bg-surface px-3 outline-none focus:border-brand focus:ring-2 focus:ring-brand-soft"
          />
          <Button type="submit" disabled={!draft.trim()} busy={busy}>Ask</Button>
        </form>
      </Card>
      <div className="mt-4">
        <Notice tone="info">The assistant can look up your account but can never move money. Never share your password or PIN.</Notice>
      </div>
    </>
  );
}
