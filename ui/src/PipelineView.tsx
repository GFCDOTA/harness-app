import { useMemo } from "react";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  type Edge,
  type Node,
  MarkerType
} from "@xyflow/react";
import { StepNode, TerminalNode } from "./StepNode";
import type { RunPayload } from "./types";

const NODE_TYPES = { step: StepNode, terminal: TerminalNode };

const X = 0;
const GAP = 132;
const NODE_W = 420;

function secs(v: number | null): string {
  return v === null ? "—" : (v / 1000).toFixed(2) + " s";
}

/**
 * Layout: a run e uma cadeia, entao a posicao sai de um contador. Nenhuma engine de
 * layout entra aqui enquanto o grafo for linear — seria dependencia sem problema.
 */
export function PipelineView({
  run,
  selectedId,
  onSelect
}: {
  run: RunPayload;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
}) {
  const { nodes, edges } = useMemo(() => {
    const steps = run.pipeline.nodes;
    const ns: Node[] = [];

    ns.push({
      id: "__start",
      type: "terminal",
      position: { x: X, y: 0 },
      data: { label: "RUN", sub: `${run.eventCount} eventos · ${secs(run.durationMs)}`, kind: "start" },
      draggable: false,
      width: NODE_W
    });

    steps.forEach((step, i) => {
      ns.push({
        id: step.id,
        type: "step",
        position: { x: X, y: (i + 1) * GAP },
        data: { step },
        selected: step.id === selectedId,
        width: NODE_W
      });
    });

    ns.push({
      id: "__end",
      type: "terminal",
      position: { x: X, y: (steps.length + 1) * GAP },
      data: {
        label: (run.terminalStatus ?? "sem terminal").toUpperCase(),
        sub: "fim da run",
        kind: "end"
      },
      draggable: false,
      width: NODE_W
    });

    const es: Edge[] = [];
    if (steps.length > 0) {
      es.push({
        id: "__start->" + steps[0]!.id,
        source: "__start",
        target: steps[0]!.id,
        markerEnd: { type: MarkerType.ArrowClosed }
      });
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
        markerEnd: { type: MarkerType.ArrowClosed }
      });
    }
    if (steps.length > 0) {
      es.push({
        id: steps[steps.length - 1]!.id + "->__end",
        source: steps[steps.length - 1]!.id,
        target: "__end",
        markerEnd: { type: MarkerType.ArrowClosed }
      });
    }
    return { nodes: ns, edges: es };
  }, [run, selectedId]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={NODE_TYPES}
      onNodeClick={(_, node) => onSelect(node.type === "step" ? node.id : null)}
      onPaneClick={() => onSelect(null)}
      fitView
      fitViewOptions={{ padding: 0.15 }}
      minZoom={0.2}
      proOptions={{ hideAttribution: false }}
    >
      <Background gap={18} size={1} />
      <Controls showInteractive={false} />
      <MiniMap pannable zoomable />
    </ReactFlow>
  );
}
