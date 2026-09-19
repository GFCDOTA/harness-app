import { useEffect, useState } from "react";
import { PipelineView } from "./PipelineView";
import { EventsView } from "./EventsView";
import { DetailPanel } from "./DetailPanel";
import { OracleView } from "./OracleView";
import { AgentView } from "./AgentView";
import { latest, probe } from "./bridge";
import { applyTheme, readTheme, type Theme } from "./theme";
import type { AgentPayload, LaunchResult, OraclePayload, RunPayload, View } from "./types";

function secs(v: number | null): string {
  return v === null ? "—" : (v / 1000).toFixed(2) + " s";
}

const TABS: { id: View; label: string }[] = [
  { id: "agent", label: "Agente" },
  { id: "pipeline", label: "Pipeline" },
  { id: "events", label: "Events" },
  { id: "oracle", label: "Oráculo" }
];

export function App() {
  const [run, setRun] = useState<RunPayload | null>(null);
  const [oracle, setOracle] = useState<OraclePayload | null>(null);
  const [view, setView] = useState<View>("pipeline");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [lastLaunch, setLastLaunch] = useState<LaunchResult | null>(null);
  // Um passo expandido por vez: mais controlavel e nao vira arvore de Natal.
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [agent, setAgent] = useState<AgentPayload | null>(null);
  const [theme, setTheme] = useState<Theme>(readTheme);

  // A API e o flag `ready` sao instalados APOS a montagem. createRoot().render() e
  // assincrono: marcar ready antes disso sinalizaria "pronto" enquanto loadRun ainda
  // era o stub, e o Java chamaria cedo. Ja custou um crash uma vez.
  useEffect(() => {
    window.inspector.loadRun = (json: string) => {
      const payload = JSON.parse(json) as RunPayload;
      latest.run = payload;
      setRun(payload);
      setSelectedId(null);
      setExpandedId(null);
      return payload.pipeline.nodes.length;
    };
    window.inspector.appendEvent = (json: string) => {
      const box = JSON.parse(json);
      setRun((prev) => {
        if (!prev) return prev;
        const next = { ...prev, boxes: [...prev.boxes, box], eventCount: prev.boxes.length + 1 };
        latest.run = next;
        return next;
      });
      return 1;
    };
    window.inspector.setOracle = (json: string) => {
      const payload = JSON.parse(json) as OraclePayload;
      latest.oracle = payload;
      setOracle(payload);
      return payload.health.length;
    };
    window.inspector.setLaunchResult = (json: string) => {
      const r = JSON.parse(json) as LaunchResult;
      latest.lastLaunch = r;
      setLastLaunch(r);
      return r.serviceId;
    };
    window.inspector.setAgent = (json: string) => {
      const payload = JSON.parse(json) as AgentPayload;
      latest.agent = payload;
      setAgent(payload);
      return payload.tools.length;
    };
    window.inspector.setView = (v: View) => {
      latest.view = v;
      setView(v);
      return v;
    };
    window.inspector.probe = probe;
    window.inspector.ready = true;
  }, []);

  useEffect(() => {
    latest.view = view;
  }, [view]);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  if (!run) {
    // O agente NAO depende de trace: operar a planta e' independente de ter uma
    // run aberta. Travar a tela inteira aqui deixaria o comando inacessivel.
    return (
      <div className="app">
        <header className="top">
          <div className="top-l">
            <h1>{agent?.project ?? "harness"}</h1>
            <div className="top-sum">sem trace carregado · control plane disponível</div>
          </div>
        </header>
        <main className="body">
          <div className="canvas">
            <AgentView agent={agent} />
          </div>
        </main>
      </div>
    );
  }

  const steps = run.pipeline.nodes;
  const selectedIdx = steps.findIndex((s) => s.id === selectedId);
  const selected = selectedIdx >= 0 ? steps[selectedIdx]! : null;
  const foraDoProcesso = steps.filter((s) => s.profile.kind !== "codigo local").length;
  const oracleDown = oracle ? oracle.serviceCount - oracle.upCount : 0;

  return (
    <div className="app">
      <header className="top">
        <div className="top-l">
          <h1>{run.runId}</h1>
          <div className="top-sum">
            <b>{steps.length}</b> passos · <b>{run.eventCount}</b> eventos ·{" "}
            <b>{secs(run.durationMs)}</b> · terminal <b>{run.terminalStatus ?? "—"}</b> ·{" "}
            <b>{foraDoProcesso}</b> passos saem do processo Python (todos nesta máquina)
          </div>
          <div className="top-src">{run.source}</div>
        </div>
        <div className="top-r">
        <nav className="views" role="tablist">
          {TABS.map((t) => (
            <button
              key={t.id}
              role="tab"
              aria-selected={view === t.id}
              className={view === t.id ? "on" : ""}
              onClick={() => setView(t.id)}
            >
              {t.label}
              {t.id === "oracle" && oracle ? (
                <span className={`tab-dot ${oracleDown > 0 ? "dot-down" : "dot-up"}`} title={`${oracle.upCount}/${oracle.serviceCount} no ar`} />
              ) : null}
            </button>
          ))}
        </nav>
        <button
          className="theme-toggle"
          onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          title={theme === "dark" ? "mudar para claro" : "mudar para escuro"}
          aria-label="alternar tema"
          data-theme-now={theme}
        >
          {theme === "dark" ? "☼" : "☽"}
        </button>
        </div>
      </header>

      <main className="body">
        <div className="canvas">
          {view === "agent" ? (
            <AgentView agent={agent} />
          ) : view === "pipeline" ? (
            <PipelineView
              run={run}
              selectedId={selectedId}
              expandedId={expandedId}
              onSelect={setSelectedId}
              onToggleExpand={(id) => setExpandedId((atual) => (atual === id ? null : id))}
              theme={theme}
            />
          ) : view === "events" ? (
            <EventsView boxes={run.boxes} />
          ) : (
            <OracleView oracle={oracle} lastLaunch={lastLaunch} />
          )}
        </div>
        {view === "pipeline" && selected ? (
          <DetailPanel step={selected} order={selectedIdx + 1} onClose={() => setSelectedId(null)} />
        ) : null}
      </main>
    </div>
  );
}
