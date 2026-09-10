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

function Secao({ titulo, children }: { titulo: string; children: React.ReactNode }) {
  return (
    <section className="dt-sec" data-sec={titulo}>
      <h4 className="dt-sec-h">{titulo}</h4>
      {children}
    </section>
  );
}

function Linha({ k, v }: { k: string; v: string }) {
  if (!v) return null;
  return (
    <div className="dt-line">
      <span className="dt-k">{k}</span>
      <span className="dt-v">{v}</span>
    </div>
  );
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

/**
 * O painel responde, nesta ordem: o que E, onde roda, como e chamado, o que recebe,
 * o que devolve, por que existe — e so entao o que aconteceu nesta execucao.
 *
 * O conteudo explicativo e DETERMINISTICO, vindo do catalogo do lado Java. Nao chama
 * LLM: uma ferramenta de estudo que inventa explicacao e pior que uma que se cala.
 */
export function DetailPanel({ step, order, onClose }: { step: Step; order: number; onClose: () => void }) {
  const persona = personaFor(step.category, step.componentFamily);
  const p = step.profile;
  const totalFields = step.measurements.reduce((n, m) => n + Object.keys(m.meta ?? {}).length, 0);

  return (
    <aside className="detail" data-detail-for={step.id}>
      <header className="dt-head">
        <span className="dt-order">{order}</span>
        <span className="dt-persona">
          <PersonaIcon persona={persona} size={26} />
        </span>
        <span className="dt-title">{p.humanName}</span>
        <button className="dt-close" onClick={onClose} aria-label="fechar painel">
          ✕
        </button>
      </header>

      <Secao titulo="identidade">
        <Linha k="componente" v={step.component ?? "—"} />
        <Linha k="categoria" v={step.category ?? "—"} />
        <Linha k="papel" v={PERSONA_LABEL[persona]} />
        <Linha k="função" v={p.role} />
      </Secao>

      <Secao titulo="onde roda">
        <Linha k="tipo" v={p.kindLabel} />
        <Linha k="host" v={p.host} />
        <Linha k="endpoint" v={p.endpoint} />
        <Linha k="transporte" v={p.transport} />
      </Secao>

      <Secao titulo="entra e sai">
        <Linha k="recebe" v={p.input} />
        <Linha k="devolve" v={p.output} />
      </Secao>

      {p.why ? (
        <Secao titulo="por que existe">
          <p className="dt-why">{p.why}</p>
        </Secao>
      ) : null}

      {p.concepts.length > 0 ? (
        <Secao titulo="conceitos para estudar">
          <div className="dt-concepts">
            {p.concepts.map((c) => (
              <span key={c} className="dt-concept">
                {c}
              </span>
            ))}
          </div>
        </Secao>
      ) : null}

      {p.code ? (
        <Secao titulo="código">
          <code className="dt-code">{p.code}</code>
        </Secao>
      ) : null}

      {!p.catalogued ? (
        <p className="dt-uncat">
          Este componente ainda não está no catálogo, então acima só aparece o que dá
          para derivar do nome. Nada foi inventado.
        </p>
      ) : null}

      <Secao titulo="nesta execução">
        <Linha k="status" v={step.status ?? "—"} />
        <Linha k="duração" v={ms(step.durationMs)} />
        <Linha k="eventos" v={`${step.eventCount} (seq ${step.seqFrom}–${step.seqTo})`} />
        {step.fallbackEntry ? <Linha k="desvio" v="entrou por fallback" /> : null}
      </Secao>

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
