import { useState } from "react";
import { PersonaIcon, PERSONA_LABEL, personaFor } from "./personas";
import type { Implementation, Measurement, Step } from "./types";

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

function Linha({ k, v, prov }: { k: string; v: string; prov?: React.ReactNode }) {
  if (!v) return null;
  return (
    <div className="dt-line">
      <span className="dt-k">{k}</span>
      <span className="dt-v">
        {v}
        {prov}
      </span>
    </div>
  );
}

/** Selo de PROCEDENCIA: separa fato conferido no codigo de explicacao minha. */
function Prov({ ok, checked }: { ok: boolean; checked: boolean }) {
  if (!checked) return <span className="prov prov-none">não verificado</span>;
  return ok ? (
    <span className="prov prov-ok">verificado</span>
  ) : (
    <span className="prov prov-warn">não confere</span>
  );
}

export function decisionClass(d: string): string {
  return d.startsWith("REGRA") ? "REGRA" : d;
}

function AbaImplementacao({ impl }: { impl: Implementation }) {
  if (!impl.declared) {
    return (
      <p className="dt-provline">
        Este componente ainda não tem implementação catalogada. Nada foi deduzido —
        preferi deixar vazio a apontar um arquivo que talvez não seja o certo.
      </p>
    );
  }
  const v = impl.verification;
  return (
    <>
      <p className="dt-provline">
        Módulo, função e bibliotecas são conferidos no código do pipeline a cada
        abertura. <b>Padrão</b> é explicação minha, não fato do código.
      </p>
      <Secao titulo="quem executa">
        <Linha k="linguagem" v={impl.language} />
        <Linha k="módulo" v={impl.module} prov={<Prov ok={v.moduleFound} checked={v.checked} />} />
        <Linha k="função" v={impl.symbol} prov={<Prov ok={v.symbolFound} checked={v.checked} />} />
        <Linha k="runtime" v={impl.runtime} />
      </Secao>

      <Secao titulo="com o quê">
        {impl.libraries.length === 0 ? (
          <p className="dt-provline">Só stdlib e código do próprio repositório.</p>
        ) : (
          <div className="dt-libs">
            {impl.libraries.map((l) => (
              <span key={l} className="dt-lib">
                {l}
                {v.librariesMissing.includes(l) ? (
                  <span className="prov prov-warn">não importa</span>
                ) : null}
              </span>
            ))}
          </div>
        )}
      </Secao>

      <Secao titulo="como decidiu">
        <div className="dt-line">
          <span className="dt-k">natureza</span>
          <span className="dt-v">
            <span className={"decision decision-" + decisionClass(impl.decision)}>
              {impl.decision}
            </span>
          </span>
        </div>
        <Linha k="LLM envolvido" v={impl.llmInvolved ? "SIM" : "NÃO"} />
        <Linha k="padrão" v={impl.pattern} prov={<span className="prov prov-edu">explicação</span>} />
      </Secao>

      {v.note ? <p className="dt-provline">{v.note}</p> : null}
    </>
  );
}

function EventRow({ m }: { m: Measurement }) {
  const [open, setOpen] = useState(false);
  const keys = Object.keys(m.meta ?? {});
  return (
    <li className={"dt-event st-text-" + (m.status ?? "unknown")}>
      <button className="dt-evhead" onClick={() => setOpen(!open)} aria-expanded={open}>
        <span className="dt-seq">{m.seq}</span>
        <span className="dt-name">{m.name}</span>
        <span className="dt-ms">{ms(m.durationMs)}</span>
        <span className="dt-caret">{keys.length > 0 ? (open ? "▾" : "▸") : ""}</span>
      </button>
      <div className="dt-evsub">
        {m.component ?? "—"} · {m.status ?? "—"}
        {keys.length > 0 ? " · " + keys.length + " campos" : ""}
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

type Aba = "resumo" | "implementacao" | "execucao";
const ABAS: { id: Aba; label: string }[] = [
  { id: "resumo", label: "Resumo" },
  { id: "implementacao", label: "Implementação" },
  { id: "execucao", label: "Execução" }
];

/**
 * O painel responde, em ordem: o que a peca E (Resumo), QUEM executa e sob que regra
 * (Implementacao), e o que aconteceu desta vez (Execucao).
 *
 * A separacao que mais importa nao e visual, e de PROCEDENCIA: modulo, funcao e
 * bibliotecas sao conferidos no codigo real a cada abertura; padrao e conceitos sao
 * explicacao. Sem isso, trocaria-se uma interface vaga por uma confiante e errada.
 */
export function DetailPanel({ step, order, onClose }: { step: Step; order: number; onClose: () => void }) {
  const [aba, setAba] = useState<Aba>("resumo");
  const persona = personaFor(step.category, step.componentFamily);
  const p = step.profile;
  const totalFields = step.measurements.reduce((n, m) => n + Object.keys(m.meta ?? {}).length, 0);

  return (
    <aside className="detail" data-detail-for={step.id} data-tab={aba}>
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

      <nav className="dt-tabs" role="tablist">
        {ABAS.map((a) => (
          <button
            key={a.id}
            role="tab"
            aria-selected={aba === a.id}
            className={"dt-tab" + (aba === a.id ? " on" : "")}
            onClick={() => setAba(a.id)}
          >
            {a.label}
          </button>
        ))}
      </nav>

      {aba === "resumo" ? (
        <>
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
          {!p.catalogued ? (
            <p className="dt-provline">
              Componente fora do catálogo: acima só aparece o que dá para derivar do
              nome. Nada foi inventado.
            </p>
          ) : null}
        </>
      ) : null}

      {aba === "implementacao" ? <AbaImplementacao impl={p.implementation} /> : null}

      {aba === "execucao" ? (
        <>
          <Secao titulo="nesta execução">
            <Linha k="status" v={step.status ?? "—"} />
            <Linha k="duração" v={ms(step.durationMs)} />
            <Linha k="eventos" v={step.eventCount + " (seq " + step.seqFrom + "–" + step.seqTo + ")"} />
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
        </>
      ) : null}
    </aside>
  );
}
