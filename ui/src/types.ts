export type Measurement = {
  seq: number;
  name: string;
  component: string | null;
  status: string | null;
  durationMs: number | null;
  detail: string;
};

/** Um PASSO do pipeline, como o Java projeta. */
export type Step = {
  id: string;
  category: string | null;
  component: string | null;
  componentFamily: string;
  status: string | null;
  durationMs: number | null;
  external: boolean;
  fallbackEntry: boolean;
  seqFrom: number;
  seqTo: number;
  eventCount: number;
  detail: string;
  measurements: Measurement[];
};

export type PipelineEdgeDto = {
  id: string;
  source: string;
  target: string;
  fallback: boolean;
};

/** Um evento cru, para a Events View (debugger secundario). */
export type Box = {
  seq: number;
  tPlusMs: number | null;
  name: string;
  category: string | null;
  component: string | null;
  status: string | null;
  durationMs: number | null;
  detail: string;
  external: boolean;
};

export type RunPayload = {
  runId: string;
  source: string;
  eventCount: number;
  spanCount: number;
  durationMs: number | null;
  terminalStatus: string | null;
  boxes: Box[];
  pipeline: { nodes: Step[]; edges: PipelineEdgeDto[] };
};

export type View = "pipeline" | "events";
