"use client";

import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import { cn } from "./ui";

/* Dashboard figures. Marks use the chart tokens (--chart-1, --chart-grid); all text uses the
   text tokens, never the series color. Every value a tooltip shows is also in a table view. */

// ---------- Stat tile ----------

export function StatTile({
  label,
  value,
  detail,
  tone = "neutral",
}: {
  label: string;
  value: ReactNode;
  /** Secondary line, e.g. a delta vs the period start. */
  detail?: ReactNode;
  tone?: "neutral" | "positive" | "negative";
}) {
  return (
    <div className="rounded-2xl border border-border bg-surface p-4">
      <p className="text-xs font-medium text-muted">{label}</p>
      <p className="tabular mt-1 truncate text-xl font-semibold tracking-tight">{value}</p>
      {detail && (
        <p className={cn("mt-0.5 text-xs", tone === "positive" ? "text-success" : tone === "negative" ? "text-danger" : "text-muted")}>
          {detail}
        </p>
      )}
    </div>
  );
}

// ---------- Balance area chart (single series: no legend, the card title names it) ----------

export type SeriesPoint = { label: string; value: number; detail?: string };

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

/** Rounds up to 1, 2, 2.5 or 5 x 10^n so axis ticks are clean numbers. */
function niceMax(value: number) {
  if (value <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find((s) => s * magnitude >= value) ?? 10;
  return step * magnitude;
}

export function AreaChart({
  points,
  formatValue,
  formatTick,
  height = 200,
  ariaLabel,
}: {
  points: SeriesPoint[];
  formatValue: (value: number) => string;
  formatTick: (value: number) => string;
  height?: number;
  ariaLabel: string;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [active, setActive] = useState<number | null>(null);
  const pad = { top: 16, right: 16, bottom: 26, left: 44 };
  const innerW = Math.max(width - pad.left - pad.right, 1);
  const innerH = height - pad.top - pad.bottom;
  const max = niceMax(Math.max(...points.map((p) => p.value), 0));
  const ticks = [0, max / 2, max];
  const x = (i: number) => pad.left + (points.length <= 1 ? innerW / 2 : (i / (points.length - 1)) * innerW);
  const y = (v: number) => pad.top + innerH - (v / max) * innerH;

  const line = points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(p.value).toFixed(1)}`).join("");
  const area = points.length ? `${line}L${x(points.length - 1).toFixed(1)},${y(0)}L${x(0).toFixed(1)},${y(0)}Z` : "";
  const lastIndex = points.length - 1;
  const xLabels = points.length > 2 ? [0, Math.floor(lastIndex / 2), lastIndex] : points.map((_, i) => i);

  function onPointer(event: PointerEvent<SVGSVGElement>) {
    const box = event.currentTarget.getBoundingClientRect();
    const relative = (event.clientX - box.left - pad.left) / innerW;
    setActive(Math.min(Math.max(Math.round(relative * lastIndex), 0), lastIndex));
  }
  function onKey(event: KeyboardEvent<SVGSVGElement>) {
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      const step = event.key === "ArrowLeft" ? -1 : 1;
      setActive((i) => Math.min(Math.max((i ?? lastIndex) + step, 0), lastIndex));
    } else if (event.key === "Escape") {
      setActive(null);
    }
  }

  const shown = active ?? null;
  const tooltipLeft = shown === null ? 0 : Math.min(Math.max(x(shown) - 80, 0), Math.max(width - 160, 0));

  return (
    <div ref={ref} className="relative w-full" style={{ height }}>
      {width > 0 && points.length > 0 && (
        <svg
          width={width}
          height={height}
          role="img"
          aria-label={ariaLabel}
          tabIndex={0}
          onPointerMove={onPointer}
          onPointerLeave={() => setActive(null)}
          onKeyDown={onKey}
          onBlur={() => setActive(null)}
          className="touch-none overflow-visible rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-brand-soft"
        >
          {ticks.map((t) => (
            <g key={t}>
              <line x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} stroke="var(--chart-grid)" strokeWidth={1} />
              <text x={pad.left - 8} y={y(t)} dy="0.32em" textAnchor="end" className="fill-muted text-[11px] tabular">
                {formatTick(t)}
              </text>
            </g>
          ))}
          {xLabels.map((i) => (
            <text
              key={i}
              x={x(i)}
              y={height - 6}
              textAnchor={i === 0 ? "start" : i === lastIndex ? "end" : "middle"}
              className="fill-muted text-[11px]"
            >
              {points[i].label}
            </text>
          ))}
          <path d={area} fill="var(--chart-1)" fillOpacity={0.1} />
          <path d={line} fill="none" stroke="var(--chart-1)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
          {shown !== null && (
            <line x1={x(shown)} x2={x(shown)} y1={pad.top} y2={pad.top + innerH} stroke="var(--muted)" strokeWidth={1} />
          )}
          {/* End marker (or the hovered point): 8px dot with a 2px surface ring. */}
          <circle
            cx={x(shown ?? lastIndex)}
            cy={y(points[shown ?? lastIndex].value)}
            r={4}
            fill="var(--chart-1)"
            stroke="var(--surface)"
            strokeWidth={2}
          />
        </svg>
      )}
      {shown !== null && (
        <div
          role="status"
          className="pointer-events-none absolute top-0 w-40 rounded-xl border border-border bg-surface px-3 py-2 text-xs shadow-sm"
          style={{ left: tooltipLeft }}
        >
          <p className="tabular text-sm font-semibold text-text">{formatValue(points[shown].value)}</p>
          <p className="text-muted">{points[shown].label}</p>
          {points[shown].detail && <p className="mt-1 text-muted">{points[shown].detail}</p>}
        </div>
      )}
    </div>
  );
}

// ---------- Ranked horizontal bars (one hue; value at the bar tip) ----------

export type BarDatum = { key: string; label: string; value: number; display: string; hint?: string };

export function BarList({ data, emptyText }: { data: BarDatum[]; emptyText: string }) {
  const rows = [...data].filter((d) => d.value > 0).sort((a, b) => b.value - a.value);
  if (!rows.length) return <p className="py-6 text-center text-sm text-muted">{emptyText}</p>;
  const max = rows[0].value;
  return (
    <ul className="space-y-3">
      {rows.map((d) => (
        <li key={d.key} title={d.hint ? `${d.label}: ${d.display} (${d.hint})` : `${d.label}: ${d.display}`}>
          <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
            <span className="truncate">{d.label}</span>
            <span className="tabular shrink-0 font-medium">{d.display}</span>
          </div>
          <div className="h-2 w-full">
            <div
              className="h-2 rounded-r-[4px] bg-chart-1"
              style={{ width: `${Math.max((d.value / max) * 100, 2)}%` }}
              aria-hidden
            />
          </div>
          {d.hint && <p className="mt-0.5 text-xs text-muted">{d.hint}</p>}
        </li>
      ))}
    </ul>
  );
}

// ---------- Meter (e.g. share of a loan repaid) ----------

export function Meter({ value, label, tone = "brand" }: { value: number; label: string; tone?: "brand" | "warning" | "danger" }) {
  const pct = Math.min(Math.max(value, 0), 1) * 100;
  const fill = { brand: "bg-chart-1", warning: "bg-warning", danger: "bg-danger" }[tone];
  const track = { brand: "bg-brand-soft", warning: "bg-warning-soft", danger: "bg-danger-soft" }[tone];
  return (
    <div role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)}>
      <div className={cn("h-2 w-full overflow-hidden rounded-full", track)}>
        <div className={cn("h-2 rounded-full", fill)} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}
