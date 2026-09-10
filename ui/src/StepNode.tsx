import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import { PersonaIcon, PERSONA_LABEL, personaFor } from "./personas";
import type { Step } from "./types";

export type StepFlowNode = Node<{ step: Step; order: number }, "step">;

function ms(v: number | null): string {
  if (v === null) return "—";
  return v >= 1000 ? (v / 1000).toFixed(2) + " s" : Math.round(v) + " ms";
}

const STATUS_MARK: Record<string, string> = {
  ok: "✓",
  failed: "✕",
  degraded: "!",
  skipped: "·",
  running: "…"
};

export function StepNode({ data, selected }: NodeProps<StepFlowNode>) {
  const s = data.step;
  const persona = personaFor(s.category, s.componentFamily);
  const status = s.status ?? "unknown";
  return (
    <div
      className={`step-node st-${status}${selected ? " is-selected" : ""}`}
      data-persona={persona}
      data-status={status}
      data-external={s.external ? "yes" : "no"}
      data-order={data.order}
      title={PERSONA_LABEL[persona]}
    >
      <Handle type="target" position={Position.Left} />
      <div className="sn-head">
        {/* a ordem de execucao no proprio no: com o grafo afastado, o numero
            continua legivel quando o texto ja nao esta. */}
        <span className="sn-order">{data.order}</span>
        <span className="sn-icon">
          <PersonaIcon persona={persona} size={30} />
        </span>
        <span className="sn-status">
          {STATUS_MARK[status] ?? "?"} {status}
        </span>
      </div>
      <div className="sn-title">{s.component ?? "—"}</div>
      <div className="sn-sub">
        <span className={`chip ${s.external ? "chip-ext" : "chip-loc"}`}>
          {s.external ? "HTTP externo" : "local"}
        </span>
        <span className="sn-cat">{s.category}</span>
      </div>
      <div className="sn-foot">
        <span className="sn-dur">{ms(s.durationMs)}</span>
        <span className="sn-ev">{s.eventCount} ev</span>
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

export type TerminalFlowNode = Node<{ label: string; sub: string; kind: "start" | "end" }, "terminal">;

export function TerminalNode({ data }: NodeProps<TerminalFlowNode>) {
  return (
    <div className={`terminal-node tn-${data.kind}`} data-terminal={data.kind}>
      {data.kind === "end" ? <Handle type="target" position={Position.Left} /> : null}
      <div className="tn-label">{data.label}</div>
      <div className="tn-sub">{data.sub}</div>
      {data.kind === "start" ? <Handle type="source" position={Position.Right} /> : null}
    </div>
  );
}
