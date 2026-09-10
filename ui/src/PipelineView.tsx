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
import type { RunPayload } from "./types";
import type { Theme } from "./theme";

const NODE_TYPES = { step: StepNode, terminal: TerminalNode };

const NODE_W = 220;
const TERM_W = 150;
const GAP = 62;
const Y = 0;

function secs(v: number | null): string {
  return v === null ? "—" : (v / 1000).toFixed(2) + " s";
}

/**
 * Layout HORIZONTAL: a run e uma cadeia, e da esquerda para a direita ela le como
 * linha do tempo. A posicao sai de um contador — nenhuma engine de layout entra
 * aqui enquanto o grafo for linear, seria dependencia sem problema.
 */
export function PipelineView({
  run,
  selectedId,
  onSelect,
  theme
}: {
  run: RunPayload;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  theme: Theme;
}) {
  const { nodes, edges } = useMemo(() => {
    const steps = run.pipeline.nodes;
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

    steps.forEach((step, i) => {
      ns.push({
        id: step.id,
        type: "step",
        position: { x, y: Y },
        data: { step, order: i + 1 },
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
    return { nodes: ns, edges: es };
  }, [run, selectedId]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={NODE_TYPES}
      colorMode={theme}
      onNodeClick={(_, node) => onSelect(node.type === "step" ? node.id : null)}
      onPaneClick={() => onSelect(null)}
      /* SEM fitView de proposito: numa cadeia longa ele encolhe os nos ate ficarem
         ilegiveis e ainda desperdica a altura da janela. Zoom fixo legivel, ancorado
         a esquerda, e o botao de fit dos Controls fica ali para a visao geral. */
      defaultViewport={{ x: 24, y: 235, zoom: 0.95 }}
      minZoom={0.15}
      maxZoom={2}
    >
      <Background gap={18} size={1} />
      {/* sem MiniMap: num grafo linear ele nao ajuda a navegar e ocupa o canto
          com um retangulo que parece defeito. */}
      <Controls showInteractive={false} />
    </ReactFlow>
  );
}
