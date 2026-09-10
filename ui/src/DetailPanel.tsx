import { useState } from "react";
import { PersonaIcon, PERSONA_LABEL, personaFor } from "./personas";
import type { Measurement, Step } from "./types";

function ms(v: number | null): string {
  if (v === null) return "—";
  return v >= 1000 ? (v / 1000).toFixed(2) + " s" : Math.round(v) + " ms";
}

function renderValue(v: unknown): string {
  if (v === null || v === undefined) return "—";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

/** Deep dive de UM evento: todas as chaves de meta, sem curadoria. */
function EventRow({ m }: { m: Measurement }) {
  const [open, setOpen] = useState(false);
  const keys = Object.keys(m.meta ?? {});
  return (
    <li className={`dt-event st-text-${m.status ?? "unknown"}`}>
      <button className="dt-evhead" onClick={() => setOpen(!open)} aria-expanded={open}>
        <span className="dt-seq">{m.seq}</span>
        <span className="dt-name">{m.name}</span>
        <span className="dt-ms">{ms(m.durationMs)}</span>
        <span className="dt-caret">{keys.length > 0 ? (open ? "▾" : "▸") : ""}</span>
      </button>
      <div className="dt-evsub">
        {m.component ?? "—"} · {m.status ?? "—"}
        {keys.length > 0 ? ` · ${keys.length} campos` : ""}
      </div>
      {open && keys.length > 0 ? (
        <table className="dt-meta">
          <tbody>
            {keys.map((k) => (
              <tr key={k} className="dt-meta-row">
                <th>{k}</th>
                <td>{renderValue(m.meta[k])}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </li>
  );
}

/** Onde vive a verdade tecnica: a caixa resume, aqui abre evento por evento. */
export function DetailPanel({ step, order, onClose }: { step: Step; order: number; onClose: () => void }) {
  const persona = personaFor(step.category, step.componentFamily);
  const totalFields = step.measurements.reduce((n, m) => n + Object.keys(m.meta ?? {}).length, 0);
  return (
    <aside className="detail" data-detail-for={step.id}>
      <header className="dt-head">
        <span className="dt-order">{order}</span>
        <span className="dt-persona">
          <PersonaIcon persona={persona} size={26} />
        </span>
        <span className="dt-title">{step.component ?? "—"}</span>
        <button className="dt-close" onClick={onClose} aria-label="fechar painel">
          ✕
        </button>
      </header>
      <p className="dt-role">{PERSONA_LABEL[persona]}</p>
      <dl className="dt-grid">
        <dt>categoria</dt><dd>{step.category}</dd>
        <dt>chamada</dt><dd>{step.external ? "HTTP externo" : "código local"}</dd>
        <dt>status</dt><dd className={`st-text-${step.status ?? "unknown"}`}>{step.status ?? "—"}</dd>
        <dt>duração</dt><dd>{ms(step.durationMs)}</dd>
        <dt>eventos</dt><dd>{step.eventCount} (seq {step.seqFrom}–{step.seqTo})</dd>
        {step.fallbackEntry ? (<><dt>desvio</dt><dd className="st-text-degraded">entrou por fallback</dd></>) : null}
      </dl>
      <h3 className="dt-h3">
        eventos deste passo <span className="dt-count">{totalFields} campos no total</span>
      </h3>
      <ul className="dt-events">
        {step.measurements.map((m) => (
          <EventRow key={m.seq} m={m} />
        ))}
      </ul>
    </aside>
  );
}
