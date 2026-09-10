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

/** Conferencia das afirmacoes sobre o codigo, feita contra o repo real. */
export type Verification = {
  checked: boolean;
  moduleFound: boolean;
  symbolFound: boolean;
  librariesFound: string[];
  librariesMissing: string[];
  fullyVerified: boolean;
  note: string;
};

/** QUEM executa. Campos de codigo sao FATO CONFERIDO; pattern e explicacao. */
export type Implementation = {
  declared: boolean;
  language: string;
  module: string;
  symbol: string;
  libraries: string[];
  runtime: string;
  decision: string;
  pattern: string;
  llmInvolved: boolean;
  verification: Verification;
};

/** O que o componente E — vem do catalogo deterministico do lado Java. */
export type Profile = {
  catalogued: boolean;
  humanName: string;
  kind: string;
  kindLabel: string;
  transport: string;
  host: string;
  endpoint: string;
  role: string;
  input: string;
  output: string;
  why: string;
  code: string;
  concepts: string[];
  implementation: Implementation;
};

export type Step = {
  id: string;
  category: string | null;
  component: string | null;
  componentFamily: string;
  status: string | null;
  durationMs: number | null;
  fallbackEntry: boolean;
  profile: Profile;
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
  /** sequence | call | fallback | implements — o tipo evita ensinar arquitetura errada. */
  kind: string;
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
  /** Onde aquilo roda, em palavras honestas. Substituiu o antigo booleano external. */
  kindLabel: string;
};

export type RunPayload = {
  runId: string;
  source: string;
  eventCount: number;
  spanCount: number;
  durationMs: number | null;
  terminalStatus: string | null;
  boxes: Box[];
  pipeline: { nodes: Step[]; edges: PipelineEdgeDto[]; calls: PipelineEdgeDto[] };
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
  /** Instante ISO da ultima escrita do arquivo. Chave de ordenacao e de exibicao. */
  when: string;
  /** O que o proprio documento diz ser sua data. Opcional, formato variavel. */
  declaredWhen: string;
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
