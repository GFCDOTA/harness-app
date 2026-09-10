import { useEffect, useState } from "react";
import { PipelineView } from "./PipelineView";
import { EventsView } from "./EventsView";
import { DetailPanel } from "./DetailPanel";
import { OracleView } from "./OracleView";
import { latest, probe } from "./bridge";
import type { LaunchResult, OraclePayload, RunPayload, View } from "./types";

function secs(v: number | null): string {
  return v === null ? "—" : (v / 1000).toFixed(2) + " s";
}

const TABS: { id: View; label: string }[] = [
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

  // A API e o flag `ready` sao instalados APOS a montagem. createRoot().render() e
  // assincrono: marcar ready antes disso sinalizaria "pronto" enquanto loadRun ainda
  // era o stub, e o Java chamaria cedo. Ja custou um crash uma vez.
  useEffect(() => {
    window.inspector.loadRun = (json: string) => {
      const payload = JSON.parse(json) as RunPayload;
      latest.run = payload;
      setRun(payload);
      setSelectedId(null);
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

  if (!run) {
    return <div className="empty">esperando a run do Java…</div>;
  }

  const steps = run.pipeline.nodes;
  const selectedIdx = steps.findIndex((s) => s.id === selectedId);
  const selected = selectedIdx >= 0 ? steps[selectedIdx]! : null;
  const external = steps.filter((s) => s.external).length;
  const oracleDown = oracle ? oracle.serviceCount - oracle.upCount : 0;

  return (
    <div className="app">
      <header className="top">
        <div className="top-l">
          <h1>{run.runId}</h1>
          <div className="top-sum">
            <b>{steps.length}</b> passos · <b>{run.eventCount}</b> eventos ·{" "}
            <b>{secs(run.durationMs)}</b> · terminal <b>{run.terminalStatus ?? "—"}</b> ·{" "}
            <b>{external}</b> passos com HTTP externo
          </div>
          <div className="top-src">{run.source}</div>
        </div>
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
      </header>

      <main className="body">
        <div className="canvas">
          {view === "pipeline" ? (
            <PipelineView run={run} selectedId={selectedId} onSelect={setSelectedId} />
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
