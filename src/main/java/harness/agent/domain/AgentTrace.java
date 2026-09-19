package harness.agent.domain;

import inspector.domain.TraceEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Construtor de eventos de trace do agente, no envelope v1.
 *
 * <p>Registra EVENTOS ESTRUTURADOS, não o texto final (missão §18): o que o
 * Inspector precisa é o encadeamento — comando, contexto, plano, chamada,
 * resultado, gate, desfecho — com span e pai, para o grafo saber quem chamou quem.
 *
 * <p>A hierarquia de spans é deliberada: a run é o comando; cada turno do modelo é
 * um span filho; cada tool é filha do turno. É assim que a Pipeline View mostra
 * "este plano gerou estas chamadas" em vez de uma fila plana.
 */
public final class AgentTrace {

    /** Categorias do agente. `category` é String no envelope, justamente para crescer. */
    public static final String CAT_AGENT = "AGENT";
    public static final String CAT_LLM = "LLM";
    public static final String CAT_TOOL = "TOOL";
    public static final String CAT_GATE = "GATE";

    private final String runId;
    private final TraceRecorder recorder;
    private final AtomicLong seq = new AtomicLong(0);
    private int spanCounter;

    public AgentTrace(final String runId, final TraceRecorder recorder) {
        this.runId = runId;
        this.recorder = recorder == null ? TraceRecorder.NOOP : recorder;
    }

    public String runId() {
        return this.runId;
    }

    /** Novo id de span, estável e legível no grafo. */
    public String newSpan() {
        return String.format("s%03d", ++this.spanCounter);
    }

    public void emit(final String spanId, final String parentSpanId, final String component, final String category,
                     final String status, final String name, final Double durationMs, final Map<String, Object> meta) {
        this.recorder.record(new TraceEvent(
                this.seq.incrementAndGet(), this.runId, spanId, parentSpanId,
                Instant.now().toString(), durationMs, component, category, status, name,
                meta == null ? Map.of() : meta));
    }

    /** Abertura da run — terminal do envelope, não um passo do pipeline. */
    public void runStarted(final String command, final Map<String, Object> context) {
        final var meta = new LinkedHashMap<String, Object>(context == null ? Map.of() : context);
        meta.put("command", command);
        emit(newSpan(), null, "harness.agent", CAT_AGENT, "started", "run.started", null, meta);
    }

    public void runFinished(final String status, final String summary, final double elapsedMs,
                            final Map<String, Object> extra) {
        final var meta = new LinkedHashMap<String, Object>(extra == null ? Map.of() : extra);
        meta.put("summary", summary);
        emit(newSpan(), null, "harness.agent", CAT_AGENT, status, "run.finished", elapsedMs, meta);
        this.recorder.close();
    }

    public void plannerCalled(final String spanId, final String model, final int attempt, final int toolCount) {
        emit(spanId, null, "ollama." + model, CAT_LLM, "started", "agent.plan",
                null, Map.of("model", model, "attempt", attempt, "toolsOffered", toolCount));
    }

    public void plannerDecided(final String spanId, final String model, final PlannerDecision decision,
                               final double elapsedMs) {
        final var meta = new LinkedHashMap<String, Object>();
        meta.put("model", model);
        meta.put("decision", decision.kind().name());
        meta.put("calls", decision.calls().stream().map(ToolCall::tool).toList());
        if (decision.message() != null) meta.put("message", decision.message());
        final var status = switch (decision.kind()) {
            case UNAVAILABLE -> "error";
            case NEEDS_HUMAN -> "degraded";
            default -> "ok";
        };
        emit(spanId, null, "ollama." + model, CAT_LLM, status, "agent.plan", elapsedMs, meta);
    }

    public void toolCalled(final String spanId, final String parentSpanId, final ToolCall call, final ToolResult result) {
        final var meta = new LinkedHashMap<String, Object>();
        meta.put("tool", call.tool());
        meta.put("args", call.args());
        meta.put("ok", result.ok());
        if (result.ok()) {
            meta.putAll(result.data());
        } else {
            meta.put("errorCode", result.errorCode());
            meta.put("errorMessage", result.errorMessage());
            meta.putAll(result.errorExtra());
        }
        emit(spanId, parentSpanId, "capability." + call.tool(), CAT_TOOL,
                result.ok() ? "ok" : "error", "tool.invoke",
                (double) result.elapsedMs(), meta);
    }

    public void toolRejected(final String spanId, final String parentSpanId, final ToolCall call,
                             final String code, final String message) {
        emit(spanId, parentSpanId, "capability." + call.tool(), CAT_TOOL, "error",
                "tool.rejected", 0.0,
                Map.of("tool", call.tool(), "args", call.args(),
                        "errorCode", code, "errorMessage", message));
    }

    public void gatesRan(final String spanId, final String parentSpanId, final String roomId,
                         final Map<String, Object> report, final double elapsedMs) {
        final var meta = new LinkedHashMap<String, Object>(report);
        meta.put("roomId", roomId);
        final var overall = String.valueOf(report.getOrDefault("overall", "INCOMPLETE"));
        emit(spanId, parentSpanId, "gate.deterministic", CAT_GATE,
                switch (overall) {
                    case "PASS" -> "ok";
                    case "WARN" -> "degraded";
                    case "UNAVAILABLE" -> "skipped";
                    default -> "error";
                },
                "gate.run", elapsedMs, meta);
    }
}
