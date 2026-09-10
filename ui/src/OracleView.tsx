import { useState } from "react";
import type { Consult, LaunchResult, OraclePayload } from "./types";

function bytes(n: number): string {
  return n >= 1024 ? (n / 1024).toFixed(1) + " KB" : n + " B";
}

function when(w: string): string {
  const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})/.exec(w);
  if (m) return `${m[3]}/${m[2]}/${m[1]} ${m[4]}:${m[5]}`;
  return w || "—";
}

function ConsultDetail({ c }: { c: Consult }) {
  return (
    <div className="consult-detail" data-consult-for={c.id}>
      <h3 className="cd-title">{c.title}</h3>
      <div className="cd-meta">
        {when(c.when)} · {bytes(c.sizeBytes)} · {c.bothSides ? "pergunta e resposta" : "só um lado registrado"}
      </div>
      {/* a previa dos DOIS lados, lado a lado */}
      <div className="cd-pair">
        <section className="cd-side cd-q">
          <h4>eu perguntei</h4>
          <pre>{c.question || "(a pergunta não ficou registrada neste formato)"}</pre>
        </section>
        <section className="cd-side cd-a">
          <h4>GPT respondeu</h4>
          <pre>{c.answer || "(sem resposta registrada)"}</pre>
        </section>
      </div>
    </div>
  );
}

/**
 * Painel do oraculo: a esteira esta viva? e o que eu e o GPT conversamos.
 *
 * O botao "ligar" dispara UMA vez, por clique do Felipe. Nao existe retentativa
 * automatica, nem reacao a "caiu" — a licao do NOC foi que ressurreicao automatica
 * custa mais do que entrega. Aqui quem decide tentar de novo e a pessoa.
 */
export function OracleView({
  oracle,
  lastLaunch
}: {
  oracle: OraclePayload | null;
  lastLaunch: LaunchResult | null;
}) {
  const [openId, setOpenId] = useState<string | null>(null);
  const [asked, setAsked] = useState<string[]>([]);

  function start(serviceId: string) {
    window.inspector.requestStart(serviceId);
    setAsked((a) => (a.includes(serviceId) ? a : [...a, serviceId]));
  }

  if (!oracle) {
    return <div className="empty">sondando a esteira…</div>;
  }
  const selected = oracle.consults.find((c) => c.id === openId) ?? oracle.consults[0] ?? null;

  return (
    <div className="oracle">
      <div className="strip-head">
        <span>a esteira</span>
        <button className="hc-start docker" onClick={() => start("docker")}>
          ligar Docker Desktop
        </button>
      </div>
      {lastLaunch ? (
        <div className={`launch-result ${lastLaunch.ok ? "ok" : "fail"}`} data-launch-for={lastLaunch.serviceId}>
          <b>{lastLaunch.serviceId}</b> · {lastLaunch.ok ? "comando OK" : "comando falhou"}
          {lastLaunch.exitCode !== null ? ` (exit ${lastLaunch.exitCode})` : ""}
          <pre>{lastLaunch.output || "(sem saída)"}</pre>
        </div>
      ) : null}
      <div className="health-strip">
        {oracle.health.map((h) => (
          <div key={h.id} className={`health-card ${h.up ? "up" : "down"}`} data-service={h.id} data-up={h.up ? "yes" : "no"}>
            <div className="hc-top">
              <span className={`dot ${h.up ? "dot-up" : "dot-down"}`} aria-hidden="true" />
              <span className="hc-label">{h.label}</span>
              <span className="hc-state">{h.up ? "no ar" : "fora do ar"}</span>
            </div>
            <div className="hc-role">{h.role}</div>
            <div className="hc-detail">
              {h.up
                ? `HTTP ${h.httpStatus} · ${h.latencyMs} ms`
                : h.detail || "sem resposta"}
            </div>
            {!h.up ? (
              <button className="hc-start" onClick={() => start(h.id)} data-start={h.id}>
                {asked.includes(h.id) ? "pedido enviado · ligar de novo" : "ligar"}
              </button>
            ) : null}
          </div>
        ))}
      </div>

      <div className="oracle-body">
        <div className="consult-list">
          <div className="cl-head">
            {oracle.consults.length} consultas ao GPT
            <span className="cl-dir">{oracle.consultsDir}</span>
          </div>
          {oracle.consults.length === 0 ? (
            <div className="empty small">
              nenhuma consulta encontrada. Aponte com <code>-DconsultsDir</code>.
            </div>
          ) : (
            oracle.consults.map((c) => (
              <button
                key={c.id}
                className={`consult-row${selected?.id === c.id ? " on" : ""}`}
                data-consult={c.id}
                onClick={() => setOpenId(c.id)}
              >
                <span className="cr-when">{when(c.when)}</span>
                <span className="cr-title">{c.title}</span>
                <span className={`cr-badge${c.bothSides ? " both" : ""}`}>
                  {c.bothSides ? "2 lados" : "1 lado"}
                </span>
              </button>
            ))
          )}
        </div>
        {selected ? <ConsultDetail c={selected} /> : null}
      </div>
    </div>
  );
}
