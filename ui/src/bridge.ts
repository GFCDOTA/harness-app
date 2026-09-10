import type { LaunchResult, OraclePayload, RunPayload, View } from "./types";

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
  setOracle: (json: string) => number;
  setLaunchResult: (json: string) => string;
  setView: (view: View) => View;
  probe: () => unknown;
  /** A UI ENFILEIRA um pedido; o Java PUXA. Ver WebBridge.drainRequests. */
  requestStart: (serviceId: string) => number;
  __drain: () => string;
};

declare global {
  interface Window {
    inspector: InspectorApi;
  }
}

/** Estado observavel pela sonda, sem obrigar o React a expor internals. */
export const latest: {
  run: RunPayload | null;
  oracle: OraclePayload | null;
  view: View;
  lastLaunch: LaunchResult | null;
} = { run: null, oracle: null, view: "pipeline", lastLaunch: null };

/**
 * Fila de pedidos da UI para o Java. Fica FORA do React de proposito: o Java puxa
 * dela a cada 400 ms por executeScript, e um estado de componente nao estaria
 * acessivel de fora. So ids conhecidos entram — o Java rejeita o resto.
 */
const queue: string[] = [];

export function installBase() {
  if (window.inspector) return;
  const notMounted = () => {
    throw new Error("UI ainda nao montou");
  };
  window.inspector = {
    ready: false,
    errors: [],
    loadRun: notMounted,
    appendEvent: notMounted,
    setOracle: notMounted,
    setLaunchResult: notMounted,
    setView: notMounted,
    probe,
    requestStart: (serviceId: string) => queue.push(serviceId),
    __drain: () => {
      const out = JSON.stringify(queue);
      queue.length = 0;
      return out;
    }
  };
  window.onerror = (m, _s, l, c) => {
    window.inspector.errors.push("onerror: " + m + " @" + l + ":" + c);
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

/** Prova por MEDIDA: a janela nativa nao e screenshotavel neste ambiente. */
export function probe(): unknown {
  const run = latest.run;
  const oracle = latest.oracle;
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
    domOrderBadges: countAll(".sn-order"),
    personas: distinct(".step-node", "data-persona"),
    statuses: distinct(".step-node", "data-status"),
    kinds: distinct(".step-node", "data-kind"),
    domRouteLines: countAll(".sn-route"),
    domSections: countAll(".dt-sec"),
    domTabs: countAll(".dt-tab"),
    domDecisionBadges: countAll(".sn-decision .decision"),
    domProvBadges: countAll(".prov"),
    activeTab: document.querySelector(".detail")?.getAttribute("data-tab") ?? null,
    domEventRows: countAll(".ev"),
    detailOpenFor: document.querySelector(".detail")?.getAttribute("data-detail-for") ?? null,
    detailMetaRows: countAll(".dt-meta-row"),
    oracleServices: oracle?.health.length ?? 0,
    oracleUp: oracle?.upCount ?? 0,
    oracleConsults: oracle?.consults.length ?? 0,
    domHealthCards: countAll(".health-card"),
    domConsultRows: countAll(".consult-row"),
    consultOpenFor: document.querySelector(".consult-detail")?.getAttribute("data-consult-for") ?? null,
    domStartButtons: countAll(".hc-start"),
    lastLaunch: latest.lastLaunch ? latest.lastLaunch.serviceId + ":" + latest.lastLaunch.ok : null,
    errors: window.inspector.errors.slice(0, 10)
  };
}
