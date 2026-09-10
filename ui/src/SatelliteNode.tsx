import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";

export type SatelliteData = {
  kind: "impl" | "deps" | "input" | "output" | "decision";
  titulo: string;
  linhas: string[];
  /** true quando o conteudo foi conferido no codigo; false quando e explicacao. */
  verificado: boolean | null;
};

export type SatelliteFlowNode = Node<SatelliteData, "satellite">;

/**
 * Sem emoji de proposito: a fonte do WebView do JavaFX nao tem esses glifos e eles
 * saem como quadradinho. O tipo do satelite ja e comunicado pela cor da borda.
 */
const MARCA: Record<SatelliteData["kind"], string> = {
  impl: "cod",
  deps: "lib",
  input: "in",
  output: "out",
  decision: "!"
};

/**
 * No SATELITE: nao e uma etapa de execucao, e uma explicacao sobre a etapa.
 *
 * Por isso o desenho e outro — borda tracejada e fundo mais apagado. Se satelite e
 * passo tivessem a mesma cara, o grafo ensinaria que "tools/reference_db.py" e um
 * servico que foi chamado, que e exatamente a confusao a evitar.
 */
export function SatelliteNode({ data }: NodeProps<SatelliteFlowNode>) {
  return (
    <div className={`sat sat-${data.kind}`} data-sat={data.kind}>
      <Handle type="target" position={Position.Bottom} />
      <Handle type="source" position={Position.Top} />
      <div className="sat-h">
        <span className="sat-i" aria-hidden="true">
          {MARCA[data.kind]}
        </span>
        {data.titulo}
        {data.verificado === true ? <span className="prov prov-ok">verificado</span> : null}
        {data.verificado === false ? <span className="prov prov-edu">explicação</span> : null}
      </div>
      {data.linhas.map((l, i) => (
        <div key={i} className="sat-l">
          {l}
        </div>
      ))}
    </div>
  );
}
