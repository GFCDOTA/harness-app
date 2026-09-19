import { useEffect, useRef, useState } from "react";
import type { AgentOutcome, AgentPayload } from "./types";

/**
 * O painel de comando — a porta de entrada da operação.
 *
 * O que ele NÃO faz: julgar. O selo de status vem do desfecho calculado no Java,
 * que por sua vez sai dos gates determinísticos. Uma tela que pintasse de verde
 * porque o modelo escreveu "pronto" seria exatamente a mentira que a missão §33
 * proíbe.
 */

const STATUS_LABEL: Record<string, string> = {
  CLEAN: "feito",
  GATE_FAILED: "gate reprovou",
  UNVERIFIED: "não verificado",
  ANSWERED: "respondido",
  NEEDS_FELIPE: "precisa de você",
  UNAVAILABLE: "indisponível",
  EXHAUSTED: "sem desfecho"
};

/**
 * O selo de CLEAN so pode dizer "validado" quando um gate REALMENTE rodou.
 *
 * Abrir um projeto muda o estado e termina CLEAN, mas nenhum gate mediu nada —
 * e a tela dizia VALIDADO assim mesmo. Numa ferramenta cuja regra e' "o veredito
 * vem do gate", isso e' o rotulo afirmando o que ninguem verificou.
 */
function statusLabel(status: string, gateCount: number): string {
  if (status === "CLEAN") return gateCount > 0 ? "validado" : "feito";
  return STATUS_LABEL[status] ?? status;
}

function Gate({ report }: { report: Record<string, unknown> }) {
  const gates = (report.gates ?? {}) as Record<string, { result: string; fails?: string[]; warns?: string[] }>;
  const overall = String(report.overall ?? "—");
  return (
    <div className="ag-gates" data-overall={overall}>
      <div className="ag-gates-head">
        gates · <b>{String(report.roomId ?? "")}</b> → <span className={`ag-badge b-${overall}`}>{overall}</span>
      </div>
      <ul>
        {Object.entries(gates).map(([name, g]) => (
          <li key={name}>
            <span className={`ag-badge b-${g.result}`}>{g.result}</span> {name}
            {(g.fails ?? []).map((f, i) => (
              <div className="ag-fail" key={i}>
                {f}
              </div>
            ))}
            {(g.warns ?? []).map((w, i) => (
              <div className="ag-warn" key={i}>
                {w}
              </div>
            ))}
          </li>
        ))}
      </ul>
    </div>
  );
}

function Outcome({ outcome }: { outcome: AgentOutcome }) {
  return (
    <div className="ag-outcome" data-status={outcome.status}>
      <div className="ag-outcome-head">
        <span className={`ag-badge b-${outcome.status}`}>
          {statusLabel(outcome.status, outcome.gateResults.length)}
        </span>
        <span className="ag-trace" title="trace desta execução">
          {outcome.traceId}
        </span>
      </div>
      <p className="ag-summary">{outcome.summary}</p>

      {outcome.actions.length > 0 ? (
        <ul className="ag-actions">
          {outcome.actions.map((a, i) => (
            <li className="ag-action" key={i} data-ok={a.ok} data-mutating={a.mutating}>
              <span className="ag-mark">{a.ok ? "✓" : "✗"}</span>
              <code>{a.tool}</code>
              <span className="ag-detail">{a.detail}</span>
            </li>
          ))}
        </ul>
      ) : null}

      {outcome.gateResults.map((g, i) => (
        <Gate report={g} key={i} />
      ))}

      {outcome.options.length > 0 ? (
        <div className="ag-options">
          precisa da sua confirmação:
          {outcome.options.map((o, i) => (
            <code key={i}>{JSON.stringify(o)}</code>
          ))}
        </div>
      ) : null}
    </div>
  );
}

export function AgentView({ agent }: { agent: AgentPayload | null }) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const lastTurnCount = useRef(0);

  // O Java devolve o resultado empurrando um payload novo. Comparar a contagem de
  // turnos e' mais confiavel que um timeout: a resposta chega quando chega.
  useEffect(() => {
    if (!agent) return;
    if (agent.turns.length !== lastTurnCount.current) {
      lastTurnCount.current = agent.turns.length;
      setBusy(false);
    }
  }, [agent]);

  if (!agent) {
    return <div className="empty">o control plane ainda não reportou estado…</div>;
  }

  const blocked = !agent.hostUp || !agent.modelUp;

  function send() {
    const cmd = text.trim();
    if (!cmd || busy || blocked) return;
    window.inspector.sendCommand(cmd);
    setText("");
    setBusy(true);
  }

  return (
    <div className="agent">
      <div className="ag-state">
        <span>
          projeto <b>{agent.project}</b>
        </span>
        <span>
          cômodo ativo <b>{agent.activeRoom ?? "—"}</b>
        </span>
        <span>
          último objeto <b>{agent.lastReferencedObject ?? "—"}</b>
        </span>
        <span>
          desfazíveis <b>{agent.pendingEdits}</b>
        </span>
        {agent.lockedObjects.length > 0 ? (
          <span className="ag-locked">travados: {agent.lockedObjects.join(", ")}</span>
        ) : null}
      </div>

      {blocked ? (
        <div className="ag-blocked">
          {!agent.hostUp ? (
            <div>
              <b>Capability host offline.</b> Nenhuma alteração é possível agora.
              <div className="ag-why">{agent.hostDetail}</div>
            </div>
          ) : null}
          {!agent.modelUp ? (
            <div>
              <b>Modelo local indisponível</b> ({agent.model}). O Harness segue
              funcionando para o caminho determinístico, mas não interpreta comandos.
              <div className="ag-why">suba o Ollama pela aba Oráculo</div>
            </div>
          ) : null}
        </div>
      ) : null}

      <div className="ag-log">
        {agent.turns.length === 0 ? (
          <div className="ag-hint">
            Escreva o que você quer mudar na planta. Por exemplo:
            <code>move a escrivaninha 30 cm para a esquerda</code>
            <code>desfaz</code>
            <code>não mexe na cama</code>
          </div>
        ) : null}
        {agent.turns.map((t, i) => (
          <div className="ag-turn" key={i}>
            <div className="ag-you">{t.command}</div>
            <div className="ag-said" data-status={t.status}>
              <span className={`ag-badge b-${t.status}`}>
                {STATUS_LABEL[t.status] ?? t.status}
              </span>
              {t.summary}
            </div>
          </div>
        ))}
        {agent.last ? <Outcome outcome={agent.last} /> : null}
        {busy ? <div className="ag-busy">executando…</div> : null}
      </div>

      <div className="ag-bar">
        <input
          className="cmd-input"
          value={text}
          placeholder={blocked ? "indisponível — veja acima" : "digite qualquer alteração…"}
          disabled={blocked || busy}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") send();
          }}
        />
        <button className="cmd-send" onClick={send} disabled={blocked || busy || !text.trim()}>
          Enviar
        </button>
      </div>

      <details className="ag-tools">
        <summary>
          {agent.tools.length} capabilities registradas · {agent.unsupported.length} ainda não
        </summary>
        <ul>
          {agent.tools.map((t) => (
            <li key={t.name}>
              <code>{t.name}</code> <span className={`ag-risk r-${t.risk}`}>{t.risk}</span>
              {t.undoable ? <span className="ag-undo">desfazível</span> : null}
              <span className="ag-tdesc">{t.description}</span>
            </li>
          ))}
        </ul>
        <div className="ag-unsup-head">ainda não implementadas — e por quê:</div>
        <ul className="ag-unsup">
          {agent.unsupported.map((u) => (
            <li key={u.name}>
              <code>{u.name}</code> <span className="ag-tdesc">{u.reason}</span>
            </li>
          ))}
        </ul>
      </details>
    </div>
  );
}
