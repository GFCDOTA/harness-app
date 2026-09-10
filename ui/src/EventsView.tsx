import type { Box } from "./types";

function ms(v: number | null): string {
  if (v === null) return "—";
  return v >= 1000 ? (v / 1000).toFixed(2) + " s" : Math.round(v) + " ms";
}

/** Debugger SECUNDARIO: a lista crua dos 27 eventos. Nao e a experiencia principal. */
export function EventsView({ boxes }: { boxes: Box[] }) {
  return (
    <div className="events" id="events-list">
      {boxes.map((b) => (
        <div key={b.seq} className={`ev cat-${b.category ?? "NONE"}`} data-category={b.category ?? "NONE"}>
          <span className="ev-seq">{b.seq}</span>
          <span className="ev-body">
            <span className="ev-name">{b.name}</span>
            <span className="ev-sub">
              <span className={`chip ${b.external ? "chip-ext" : "chip-loc"}`}>
                {b.external ? "HTTP externo" : "local"}
              </span>
              {b.component ?? "—"}
              {b.detail ? " · " + b.detail : ""}
            </span>
          </span>
          <span className="ev-right">
            <span className={`st-text-${b.status ?? "unknown"}`}>{b.status ?? "—"}</span>
            <span className="ev-ms">{ms(b.durationMs)}</span>
            <span className="ev-t">{b.tPlusMs === null ? "" : "t+" + (b.tPlusMs / 1000).toFixed(2) + "s"}</span>
          </span>
        </div>
      ))}
    </div>
  );
}
