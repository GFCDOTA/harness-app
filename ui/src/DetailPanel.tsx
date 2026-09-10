import { PersonaIcon, PERSONA_LABEL, personaFor } from "./personas";
import type { Step } from "./types";

function ms(v: number | null): string {
  if (v === null) return "—";
  return v >= 1000 ? (v / 1000).toFixed(2) + " s" : Math.round(v) + " ms";
}

/** Onde vive a verdade tecnica: a caixa resume, aqui abre evento por evento. */
export function DetailPanel({ step, onClose }: { step: Step; onClose: () => void }) {
  const persona = personaFor(step.category, step.componentFamily);
  return (
    <aside className="detail" data-detail-for={step.id}>
      <header className="dt-head">
        <span className="dt-persona">
          <PersonaIcon persona={persona} size={28} />
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
      <h3 className="dt-h3">eventos deste passo</h3>
      <ul className="dt-events">
        {step.measurements.map((m) => (
          <li key={m.seq} className={`st-text-${m.status ?? "unknown"}`}>
            <span className="dt-seq">{m.seq}</span>
            <span className="dt-name">{m.name}</span>
            <span className="dt-ms">{ms(m.durationMs)}</span>
            {m.detail ? <span className="dt-meta">{m.detail}</span> : null}
          </li>
        ))}
      </ul>
    </aside>
  );
}
