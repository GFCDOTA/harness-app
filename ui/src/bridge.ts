import type { RunPayload, View } from "./types";

/**
 * A fronteira Java -> JS, do lado do JS. O Java chama estes metodos via
 * WebEngine.executeScript() passando UMA string JSON. Nada de JSObject (deprecated
 * e marcado para remocao) e nenhum objeto Java exposto ao JavaScript.
 */
export type InspectorApi = {
  ready: boolean;
  errors: string[];
  loadRun: (json: string) => number;
  appendEvent: (json: string) => number;
  setView: (view: View) => View;
  probe: () => unknown;
};

declare global {
  interface Window {
    inspector: InspectorApi;
  }
}

/** Estado observavel pela sonda, sem obrigar o React a expor internals. */
export const latest: { run: RunPayload | null; view: View } = { run: null, view: "pipeline" };

export function installBase() {
  if (window.inspector) return;
  window.inspector = {
    ready: false,
    errors: [],
    loadRun: () => {
      throw new Error("UI ainda nao montou");
    },
    appendEvent: () => {
      throw new Error("UI ainda nao montou");
    },
    setView: () => {
      throw new Error("UI ainda nao montou");
    },
    probe
  };
  window.onerror = (m, _s, l, c) => {
    window.inspector.errors.push(`onerror: ${m} @${l}:${c}`);
    return false;
  };
}

function countAll(sel: string): number {
  return document.querySelectorAll(sel).length;
}

function distinct(sel: string, attr: string): string[] {
  const out = new Set<string>();
  document.querySelectorAll(sel).forEach((el) => {
    const v = el.getAttribute(attr);
    if (v) out.add(v);
  });
  return [...out].sort();
}

/** Prova por MEDIDA, nao por captura de tela: a janela nativa nao e screenshotavel aqui. */
export function probe(): unknown {
  const run = latest.run;
  return {
    view: latest.view,
    runId: run?.runId ?? null,
    payloadEvents: run?.eventCount ?? 0,
    payloadSteps: run?.pipeline.nodes.length ?? 0,
    payloadEdges: run?.pipeline.edges.length ?? 0,
    payloadFallbackEdges: run?.pipeline.edges.filter((e) => e.fallback).length ?? 0,
    domStepNodes: countAll(".step-node"),
    domTerminals: countAll(".terminal-node"),
    domEdges: countAll(".react-flow__edge"),
    domPersonaIcons: countAll(".step-node svg"),
    personas: distinct(".step-node", "data-persona"),
    statuses: distinct(".step-node", "data-status"),
    externalNodes: document.querySelectorAll('.step-node[data-external="yes"]').length,
    domEventRows: countAll(".ev"),
    detailOpenFor: document.querySelector(".detail")?.getAttribute("data-detail-for") ?? null,
    errors: window.inspector.errors.slice(0, 10)
  };
}
