import { useEffect, useState } from "react";
import { PipelineView } from "./PipelineView";
import { EventsView } from "./EventsView";
import { DetailPanel } from "./DetailPanel";
import { latest, probe } from "./bridge";
import type { RunPayload, View } from "./types";

function secs(v: number | null): string {
  return v === null ? "—" : (v / 1000).toFixed(2) + " s";
}

export function App() {
  const [run, setRun] = useState<RunPayload | null>(null);
  const [view, setView] = useState<View>("pipeline");
  const [selectedId, setSelectedId] = useState<string | null>(null);

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
  const selected = steps.find((s) => s.id === selectedId) ?? null;
  const external = steps.filter((s) => s.external).length;

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
          <button
            role="tab"
            aria-selected={view === "pipeline"}
            className={view === "pipeline" ? "on" : ""}
            onClick={() => setView("pipeline")}
          >
            Pipeline
          </button>
          <button
            role="tab"
            aria-selected={view === "events"}
            className={view === "events" ? "on" : ""}
            onClick={() => setView("events")}
          >
            Events
          </button>
        </nav>
      </header>

      <main className="body">
        <div className="canvas">
          {view === "pipeline" ? (
            <PipelineView run={run} selectedId={selectedId} onSelect={setSelectedId} />
          ) : (
            <EventsView boxes={run.boxes} />
          )}
        </div>
        {selected ? <DetailPanel step={selected} onClose={() => setSelectedId(null)} /> : null}
      </main>
    </div>
  );
}
