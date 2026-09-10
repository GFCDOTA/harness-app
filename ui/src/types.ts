export type Measurement = {
  seq: number;
  name: string;
  component: string | null;
  status: string | null;
  durationMs: number | null;
  detail: string;
  ts: string | null;
  spanId: string | null;
  parentSpanId: string | null;
  /** meta CRU do envelope: o deep-dive mostra tudo, nao so o que foi curado. */
  meta: Record<string, unknown>;
};

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

export type ServiceHealth = {
  id: string;
  label: string;
  role: string;
  up: boolean;
  httpStatus: number | null;
  latencyMs: number | null;
  detail: string;
  checkedAt: string;
};

export type Consult = {
  id: string;
  title: string;
  when: string;
  sizeBytes: number;
  question: string;
  answer: string;
  bothSides: boolean;
};

export type OraclePayload = {
  health: ServiceHealth[];
  consults: Consult[];
  consultsDir: string;
  upCount: number;
  serviceCount: number;
};

export type LaunchResult = {
  serviceId: string;
  ok: boolean;
  exitCode: number | null;
  output: string;
  startedAt: string;
};

export type View = "pipeline" | "events" | "oracle";
