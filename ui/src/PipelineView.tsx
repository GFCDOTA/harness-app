import { useMemo } from "react";
import {
  Background,
  Controls,
  ReactFlow,
  type Edge,
  type Node,
  MarkerType
} from "@xyflow/react";
import { StepNode, TerminalNode } from "./StepNode";
import { SatelliteNode, type SatelliteData } from "./SatelliteNode";
import type { RunPayload, Step } from "./types";
import type { Theme } from "./theme";

const NODE_TYPES = { step: StepNode, terminal: TerminalNode, satellite: SatelliteNode };

const NODE_W = 220;
const TERM_W = 150;
const GAP = 62;
const Y = 0;
const SAT_W = 200;
const ACIMA = -168;
const ABAIXO = 190;

function secs(v: number | null): string {
  return v === null ? "—" : (v / 1000).toFixed(2) + " s";
}

/**
 * Satelites de um passo. TUDO aqui vem do payload do Java — implementacao conferida
 * no codigo, entrada/saida do catalogo, decisao do trace. O front NAO deduz nada.
 */
function satelitesDe(step: Step): SatelliteData[] {
  const p = step.profile;
  const impl = p.implementation;
  const out: SatelliteData[] = [];

  if (impl.declared) {
    out.push({
      kind: "impl",
      titulo: "implementação",
      linhas: [impl.module, impl.symbol + "()", impl.runtime].filter(Boolean),
      verificado: impl.verification.checked ? impl.verification.fullyVerified : null
    });
    if (impl.libraries.length > 0) {
      out.push({
        kind: "deps",
        titulo: "usa",
        linhas: impl.libraries,
        verificado: impl.verification.checked
          ? impl.verification.librariesMissing.length === 0
          : null
      });
    }
  }
  if (p.input) out.push({ kind: "input", titulo: "recebe", linhas: [p.input], verificado: false });
  if (p.output) out.push({ kind: "output", titulo: "devolve", linhas: [p.output], verificado: false });
  if (step.fallbackEntry) {
    out.push({
      kind: "decision",
      titulo: "entrou por fallback",
      linhas: ["o caminho pretendido falhou"],
      verificado: true
    });
  }
  return out;
}

/**
 * Layout HORIZONTAL: a run e uma cadeia e le como linha do tempo.
 *
 * Clicar em "explorar" abre satelites ACIMA (o que a etapa e) e ABAIXO (o que entra
 * e sai), e acende as chamadas REAIS para passos que ja existem no pipeline — sem
 * duplicar no. Um passo expandido por vez, para o canvas nao virar arvore de Natal.
 */
export function PipelineView({
  run,
  selectedId,
  expandedId,
  onSelect,
  onToggleExpand,
  theme
}: {
  run: RunPayload;
  selectedId: string | null;
  expandedId: string | null;
  onSelect: (id: string | null) => void;
  onToggleExpand: (id: string) => void;
  theme: Theme;
}) {
  const { nodes, edges } = useMemo(() => {
    const steps = run.pipeline.nodes;
    const posX = new Map<string, number>();
    const ns: Node[] = [];
    let x = 0;

    ns.push({
      id: "__start",
      type: "terminal",
      position: { x, y: Y + 26 },
      data: { label: "RUN", sub: `${run.eventCount} ev · ${secs(run.durationMs)}`, kind: "start" },
      draggable: false,
      width: TERM_W
    });
    x += TERM_W + GAP;

    const chamados = new Set(
      run.pipeline.calls.filter((c) => c.source === expandedId).map((c) => c.target)
    );

    steps.forEach((step, i) => {
      posX.set(step.id, x);
      const papel =
        expandedId === null
          ? "normal"
          : step.id === expandedId
            ? "expandido"
            : chamados.has(step.id)
              ? "chamado"
              : "apagado";
      ns.push({
        id: step.id,
        type: "step",
        position: { x, y: Y },
        data: { step, order: i + 1, papel, expandido: step.id === expandedId, onToggle: onToggleExpand },
        selected: step.id === selectedId,
        width: NODE_W
      });
      x += NODE_W + GAP;
    });

    ns.push({
      id: "__end",
      type: "terminal",
      position: { x, y: Y + 26 },
      data: {
        label: (run.terminalStatus ?? "sem terminal").toUpperCase(),
        sub: "fim da run",
        kind: "end"
      },
      draggable: false,
      width: TERM_W
    });

    const arrow = { type: MarkerType.ArrowClosed };
    const es: Edge[] = [];
    if (steps.length > 0) {
      es.push({ id: "__start->" + steps[0]!.id, source: "__start", target: steps[0]!.id, markerEnd: arrow });
    }
    for (const e of run.pipeline.edges) {
      es.push({
        id: e.id,
        source: e.source,
        target: e.target,
        label: e.fallback ? "fallback" : undefined,
        className: e.fallback ? "fallback" : undefined,
        animated: e.fallback,
        style: e.fallback ? { stroke: "#BA7517", strokeWidth: 2, strokeDasharray: "6 4" } : undefined,
        markerEnd: arrow
      });
    }
    if (steps.length > 0) {
      es.push({
        id: steps[steps.length - 1]!.id + "->__end",
        source: steps[steps.length - 1]!.id,
        target: "__end",
        markerEnd: arrow
      });
    }

    // --- expansao semantica ---
    if (expandedId !== null) {
      const alvo = steps.find((s) => s.id === expandedId);
      const baseX = posX.get(expandedId);
      if (alvo && baseX !== undefined) {
        const sats = satelitesDe(alvo);
        const acima = sats.filter((s) => s.kind === "impl" || s.kind === "deps");
        const abaixo = sats.filter((s) => s.kind !== "impl" && s.kind !== "deps");

        const coloca = (lista: SatelliteData[], y: number) => {
          const largura = lista.length * SAT_W + (lista.length - 1) * 24;
          let sx = baseX + NODE_W / 2 - largura / 2;
          lista.forEach((sat, i) => {
            const id = "sat-" + expandedId + "-" + sat.kind + "-" + i;
            ns.push({
              id,
              type: "satellite",
              position: { x: sx, y },
              data: sat,
              draggable: false,
              width: SAT_W
            });
            // pontilhada: NAO e execucao, e explicacao sobre a etapa
            es.push({
              id: "e-" + id,
              source: y < 0 ? id : expandedId,
              target: y < 0 ? expandedId : id,
              className: "implements",
              style: { stroke: "var(--faint)", strokeWidth: 1.5, strokeDasharray: "2 4" }
            });
            sx += SAT_W + 24;
          });
        };
        coloca(acima, ACIMA);
        coloca(abaixo, ABAIXO);

        // chamadas REAIS, provadas por parentSpanId
        for (const c of run.pipeline.calls) {
          if (c.source !== expandedId) continue;
          es.push({
            id: "call-" + c.id,
            source: c.source,
            target: c.target,
            label: "chama",
            className: "callEdge",
            animated: true,
            style: { stroke: "var(--ok)", strokeWidth: 2.5 },
            markerEnd: { type: MarkerType.ArrowClosed }
          });
        }
      }
    }

    return { nodes: ns, edges: es };
  }, [run, selectedId, expandedId, onToggleExpand]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={NODE_TYPES}
      colorMode={theme}
      onNodeClick={(_, node) => onSelect(node.type === "step" ? node.id : null)}
      onPaneClick={() => onSelect(null)}
      defaultViewport={{ x: 24, y: 235, zoom: 0.95 }}
      minZoom={0.15}
      maxZoom={2}
    >
      <Background gap={18} size={1} />
      <Controls showInteractive={false} />
    </ReactFlow>
  );
}
