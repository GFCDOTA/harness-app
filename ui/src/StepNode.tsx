import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import { PersonaIcon, PERSONA_LABEL, personaFor } from "./personas";
import { decisionClass } from "./DetailPanel";
import type { Step } from "./types";

export type StepFlowNode = Node<
  {
    step: Step;
    order: number;
    papel: "normal" | "expandido" | "chamado" | "apagado";
    expandido: boolean;
    onToggle: (id: string) => void;
  },
  "step"
>;

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

/** De onde para onde a chamada vai. Responde onde a coisa roda sem abrir o painel. */
function rota(s: Step): string {
  const p = s.profile;
  if (p.kind === "codigo local") return "no processo Python";
  const alvo = p.host.split("—")[0].trim() || p.kindLabel;
  return p.endpoint ? "Python → " + alvo + " " + p.endpoint : "Python → " + alvo;
}

export function StepNode({ data, selected }: NodeProps<StepFlowNode>) {
  const s = data.step;
  const persona = personaFor(s.category, s.componentFamily);
  const status = s.status ?? "unknown";
  return (
    <div
      className={"step-node st-" + status + (selected ? " is-selected" : "")}
      data-persona={persona}
      data-status={status}
      data-kind={s.profile.kind}
      data-order={data.order}
      data-papel={data.papel}
      title={PERSONA_LABEL[persona]}
    >
      <Handle type="target" position={Position.Left} />
      <div className="sn-head">
        <span className="sn-order">{data.order}</span>
        <span className="sn-icon">
          <PersonaIcon persona={persona} size={26} />
        </span>
        <span className="sn-status">
          {STATUS_MARK[status] ?? "?"} {status}
        </span>
      </div>
      <div className="sn-title">{s.profile.humanName}</div>
      <div className="sn-tech">{s.component ?? "—"}</div>
      <div className="sn-route">{rota(s)}</div>
      <div className="sn-decision">
        {s.profile.implementation.decision ? (
          <span className={"decision decision-" + decisionClass(s.profile.implementation.decision)}>
            {s.profile.implementation.decision}
          </span>
        ) : null}
      </div>
      <div className="sn-foot">
        <span className={"chip chip-" + s.profile.kind.replace(/ /g, "-")}>{s.profile.kindLabel}</span>
        <span className="sn-dur">{ms(s.durationMs)}</span>
      </div>
      {/* Botao EXPLICITO: expansao em hover transformaria o canvas em arvore de Natal. */}
      <button
        className="sn-explore"
        onClick={(e) => {
          e.stopPropagation();
          data.onToggle(s.id);
        }}
      >
        {data.expandido ? "recolher" : "explorar etapa"}
      </button>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

export type TerminalFlowNode = Node<{ label: string; sub: string; kind: "start" | "end" }, "terminal">;

export function TerminalNode({ data }: NodeProps<TerminalFlowNode>) {
  return (
    <div className={"terminal-node tn-" + data.kind} data-terminal={data.kind}>
      {data.kind === "end" ? <Handle type="target" position={Position.Left} /> : null}
      <div className="tn-label">{data.label}</div>
      <div className="tn-sub">{data.sub}</div>
      {data.kind === "start" ? <Handle type="source" position={Position.Right} /> : null}
    </div>
  );
}
