import { useMemo, useState } from "react";
import type { Consult, LaunchResult, OraclePayload } from "./types";

function bytes(n: number): string {
  return n >= 1024 ? (n / 1024).toFixed(1) + " KB" : n + " B";
}

/**
 * Formata o instante ISO da ULTIMA ESCRITA do arquivo — a mesma chave por que a
 * lista ordena. A data que o documento declara de si mesmo vai separada, porque e
 * opcional e nem sempre bate.
 */
function when(iso: string): string {
  if (!iso) return "sem data";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p2 = (n: number) => String(n).padStart(2, "0");
  const hoje = new Date();
  const mesmoDia =
    d.getFullYear() === hoje.getFullYear() &&
    d.getMonth() === hoje.getMonth() &&
    d.getDate() === hoje.getDate();
  const hora = `${p2(d.getHours())}:${p2(d.getMinutes())}`;
  return mesmoDia ? `hoje ${hora}` : `${p2(d.getDate())}/${p2(d.getMonth() + 1)}/${d.getFullYear()} ${hora}`;
}

function ConsultDetail({ c }: { c: Consult }) {
  const declarada = c.declaredWhen && c.declaredWhen.trim() !== "";
  return (
    <div className="consult-detail" data-consult-for={c.id}>
      <h3 className="cd-title">{c.title}</h3>
      <div className="cd-meta">
        {when(c.when)} · {bytes(c.sizeBytes)} ·{" "}
        {c.bothSides ? "pergunta e resposta" : "só um lado registrado"}
        {declarada ? ` · documento diz ${c.declaredWhen}` : ""}
      </div>
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
  const [filtro, setFiltro] = useState("");

  function start(serviceId: string) {
    window.inspector.requestStart(serviceId);
    setAsked((a) => (a.includes(serviceId) ? a : [...a, serviceId]));
  }

  const visiveis = useMemo(() => {
    if (!oracle) return [];
    const q = filtro.trim().toLowerCase();
    if (!q) return oracle.consults;
    return oracle.consults.filter(
      (c) =>
        c.title.toLowerCase().includes(q) ||
        c.id.toLowerCase().includes(q) ||
        c.answer.toLowerCase().includes(q) ||
        c.question.toLowerCase().includes(q)
    );
  }, [oracle, filtro]);

  if (!oracle) {
    return <div className="empty">sondando a esteira…</div>;
  }
  const selected = visiveis.find((c) => c.id === openId) ?? visiveis[0] ?? null;

  return (
    <div className="oracle">
      <div className="strip-head">
        <span>
          a esteira · <b>{oracle.upCount}</b>/{oracle.serviceCount} no ar
        </span>
        <button className="hc-start docker" onClick={() => start("docker")}>
          ligar Docker Desktop
        </button>
      </div>
      {lastLaunch ? (
        <div
          className={`launch-result ${lastLaunch.ok ? "ok" : "fail"}`}
          data-launch-for={lastLaunch.serviceId}
        >
          <b>{lastLaunch.serviceId}</b> · {lastLaunch.ok ? "comando OK" : "comando falhou"}
          {lastLaunch.exitCode !== null ? ` (exit ${lastLaunch.exitCode})` : ""}
          <pre>{lastLaunch.output || "(sem saída)"}</pre>
        </div>
      ) : null}

      <div className="health-strip">
        {oracle.health.map((h) => (
          <div
            key={h.id}
            className={`health-card ${h.up ? "up" : "down"}`}
            data-service={h.id}
            data-up={h.up ? "yes" : "no"}
          >
            <div className="hc-top">
              <span className={`dot ${h.up ? "dot-up" : "dot-down"}`} aria-hidden="true" />
              <span className="hc-label">{h.label}</span>
              <span className="hc-state">{h.up ? "no ar" : "fora do ar"}</span>
            </div>
            <div className="hc-role">{h.role}</div>
            <div className="hc-detail">
              {h.up ? `HTTP ${h.httpStatus} · ${h.latencyMs} ms` : h.detail || "sem resposta"}
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
            <div className="cl-count">
              {filtro ? `${visiveis.length} de ${oracle.consults.length}` : `${oracle.consults.length}`} consultas
              ao GPT
              <span className="cl-dir">{oracle.consultsDir}</span>
            </div>
            <input
              className="cl-search"
              type="search"
              placeholder="filtrar por título ou conteúdo"
              value={filtro}
              onChange={(e) => setFiltro(e.target.value)}
            />
          </div>
          <div className="cl-scroll">
            {visiveis.length === 0 ? (
              <div className="empty small">
                {oracle.consults.length === 0
                  ? "nenhuma consulta encontrada. Aponte com -DconsultsDir."
                  : "nada bate com esse filtro."}
              </div>
            ) : (
              visiveis.map((c) => (
                <button
                  key={c.id}
                  className={`consult-row${selected?.id === c.id ? " on" : ""}`}
                  data-consult={c.id}
                  onClick={() => setOpenId(c.id)}
                >
                  <span className="cr-top">
                    <span className="cr-when">{when(c.when)}</span>
                    <span className={`cr-badge${c.bothSides ? " both" : ""}`}>
                      {c.bothSides ? "2 lados" : "1 lado"}
                    </span>
                  </span>
                  <span className="cr-title">{c.title}</span>
                </button>
              ))
            )}
          </div>
        </div>
        {selected ? <ConsultDetail c={selected} /> : null}
      </div>
    </div>
  );
}
