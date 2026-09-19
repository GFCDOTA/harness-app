package harness.agent.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O laço do agente — e a GOVERNANÇA em volta dele.
 *
 * <p>O que este objeto garante, e que nenhum modelo garante sozinho:
 *
 * <ul>
 *   <li>toda chamada passa pelo {@link ToolRegistry} antes de tocar o sistema;
 *   <li>risco HIGH nunca roda sem confirmação humana;
 *   <li>alteração geométrica dispara os gates AUTOMATICAMENTE — o sucesso não é o
 *       que o modelo diz, é o que o gate mede (missão §33);
 *   <li>existe teto de tentativas: sem desfecho, o comando vira NEEDS_FELIPE, nunca
 *       um laço infinito (missão §12);
 *   <li>o estado operacional aprende com o RESULTADO real, não com a narrativa.
 * </ul>
 *
 * <p>Sem framework e sem I/O: tudo entra por port. É o que permite testar o laço
 * inteiro — inclusive modelo malformado, tool inexistente e host fora do ar — sem
 * SketchUp, sem Ollama e sem Python.
 */
public final class AgentRuntime {

    /** Tools cujo efeito exige veredito de gate antes de chamar de "pronto". */
    private static final List<String> GEOMETRY_TOOLS =
            List.of("move_object", "rotate_object", "scale_object", "align_object",
                    "place_against_wall", "create_object", "delete_object");

    private final CapabilityHost host;
    private final LlmPlanner planner;
    private final ToolRegistry registry;
    private final AgentState state;
    private final int maxAttempts;
    private final boolean autoApproveHigh;

    public AgentRuntime(final CapabilityHost host, final LlmPlanner planner, final ToolRegistry registry,
                        final AgentState state, final int maxAttempts, final boolean autoApproveHigh) {
        this.host = host;
        this.planner = planner;
        this.registry = registry;
        this.state = state;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.autoApproveHigh = autoApproveHigh;
    }

    public AgentState state() {
        return this.state;
    }

    /** Executa um comando em linguagem natural de ponta a ponta. */
    public AgentOutcome execute(final String command, final AgentTrace trace) {
        final var startedAt = System.nanoTime();
        final var actions = new ArrayList<AgentOutcome.Action>();
        final var gateResults = new ArrayList<Map<String, Object>>();
        final var history = new ArrayList<LlmPlanner.Step>();

        trace.runStarted(command, contextMeta());

        if (!this.host.isAlive()) {
            return finish(trace, startedAt, AgentOutcome.Status.UNAVAILABLE,
                    "O capability host não está atendendo — nenhuma alteração é possível agora. "
                            + "Suba-o pelo painel de serviços e tente de novo.",
                    actions, gateResults, List.of(), command);
        }
        if (this.registry.isEmpty()) {
            return finish(trace, startedAt, AgentOutcome.Status.UNAVAILABLE,
                    "Nenhuma capability registrada: o host subiu mas não publicou a tabela de tools.",
                    actions, gateResults, List.of(), command);
        }
        if (!this.planner.isAvailable()) {
            return finish(trace, startedAt, AgentOutcome.Status.UNAVAILABLE,
                    "O modelo local (Ollama) está fora do ar. O Harness segue funcionando para "
                            + "as operações determinísticas, mas não consigo interpretar o comando.",
                    actions, gateResults, List.of(), command);
        }

        for (var attempt = 1; attempt <= this.maxAttempts; attempt++) {
            final var planSpan = trace.newSpan();
            trace.plannerCalled(planSpan, this.planner.modelName(), attempt, this.registry.all().size());

            final var plannerStartedAt = System.nanoTime();
            PlannerDecision decision;
            try {
                decision = this.planner.plan(context(command), this.registry.all(), history);
            } catch (final RuntimeException ex) {
                decision = PlannerDecision.unavailable(
                        "o modelo falhou: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            trace.plannerDecided(planSpan, this.planner.modelName(), decision, ms(plannerStartedAt));

            switch (decision.kind()) {
                case UNAVAILABLE -> {
                    return finish(trace, startedAt, AgentOutcome.Status.UNAVAILABLE,
                            "Não consegui interpretar o comando: " + decision.message(),
                            actions, gateResults, List.of(), command);
                }
                case NEEDS_HUMAN -> {
                    return finish(trace, startedAt, AgentOutcome.Status.NEEDS_FELIPE,
                            decision.message(), actions, gateResults, List.of(), command);
                }
                case FINAL -> {
                    final var status = concluding(actions, gateResults);
                    return finish(trace, startedAt, status, summarize(decision.message(), actions),
                            actions, gateResults, List.of(), command);
                }
                case CALL_TOOLS -> {
                    for (final var call : decision.calls()) {
                        final var admission = this.registry.admit(call, this.autoApproveHigh);
                        if (admission.verdict() == ToolRegistry.Admission.Verdict.NEEDS_CONFIRMATION) {
                            trace.toolRejected(trace.newSpan(), planSpan, call,
                                    admission.code(), admission.message());
                            return finish(trace, startedAt, AgentOutcome.Status.NEEDS_FELIPE,
                                    admission.message() + " — confirme para eu seguir.",
                                    actions, gateResults,
                                    List.of(Map.of("tool", call.tool(), "args", call.args())),
                                    command);
                        }
                        if (!admission.allowed()) {
                            // Recusa é DADO para o modelo: ele precisa ver o erro
                            // para escolher outra tool, não ser interrompido.
                            final var rejected = ToolResult.failure(
                                    call.tool(), admission.code(), admission.message(), Map.of(), 0);
                            trace.toolRejected(trace.newSpan(), planSpan, call,
                                    admission.code(), admission.message());
                            history.add(new LlmPlanner.Step(call, rejected));
                            actions.add(action(call, rejected, false));
                            continue;
                        }

                        final var result = this.host.invoke(call);
                        trace.toolCalled(trace.newSpan(), planSpan, call, result);
                        this.state.observe(result);
                        history.add(new LlmPlanner.Step(call, result));
                        actions.add(action(call, result, admission.spec().mutates()));

                        if (result.ok() && touchesGeometry(call.tool())) {
                            final var report = runGates(trace, planSpan, result, history);
                            if (report != null) gateResults.add(report);
                        }
                    }
                }
            }
        }

        return finish(trace, startedAt, AgentOutcome.Status.EXHAUSTED,
                "Gastei as " + this.maxAttempts + " tentativas sem chegar a um desfecho. "
                        + "O que fiz está no trace e continua desfazível.",
                actions, gateResults, List.of(), command);
    }

    /**
     * Roda os gates sobre o cômodo que a alteração tocou.
     *
     * <p>Automático de propósito: se rodar gate fosse escolha do modelo, "esqueci de
     * validar" viraria um desfecho possível. Aqui não é.
     */
    private Map<String, Object> runGates(final AgentTrace trace, final String parentSpan,
                                         final ToolResult moved, final List<LlmPlanner.Step> history) {
        final var roomId = moved.data().get("roomId");
        final var room = (roomId instanceof String declared && !declared.isBlank())
                ? declared
                : this.state.activeRoom().orElse(null);
        if (room == null || room.isBlank()) return null;
        if (this.registry.find("run_gates").isEmpty()) return null;

        final var call = new ToolCall("run_gates", Map.of("room_id", room));
        final var startedAt = System.nanoTime();
        final var result = this.host.invoke(call);
        history.add(new LlmPlanner.Step(call, result));
        if (!result.ok()) {
            trace.toolCalled(trace.newSpan(), parentSpan, call, result);
            return Map.of("roomId", room, "overall", "UNAVAILABLE",
                    "detail", result.summary());
        }
        trace.gatesRan(trace.newSpan(), parentSpan, room, result.data(), ms(startedAt));
        return result.data();
    }

    private static boolean touchesGeometry(final String tool) {
        return GEOMETRY_TOOLS.contains(tool);
    }

    /**
     * Operações de UM significado só: o Harness descreve, o modelo não.
     *
     * <p>Um {@code undo} desfaz — não há segunda leitura. Deixar a frase visível por
     * conta do modelo já produziu, numa execução real, "A escrivaninha foi movida
     * 30 cm para a esquerda" como resumo de um DESFAZER. Os dados estavam certos e a
     * manchete mentia, que é a pior combinação: quem lê rápido lê a manchete.
     */
    private static final List<String> SELF_DESCRIBING =
            List.of("undo", "redo", "restore_snapshot", "restore_last_clean");

    private static String summarize(final String fromModel,
                                    final List<AgentOutcome.Action> actions) {
        final var mutations = actions.stream()
                .filter(action -> action.ok() && action.mutating())
                .toList();
        if (mutations.size() == 1 && SELF_DESCRIBING.contains(mutations.get(0).tool())) {
            return mutations.get(0).detail();
        }
        return fromModel;
    }

    /**
     * O veredito final sai dos GATES, não do texto do modelo.
     *
     * <p>E "mudou o sistema" sai do {@code mutates} declarado na tool: uma consulta
     * bem-sucedida é ANSWERED, não CLEAN. Dizer CLEAN para quem só perguntou "onde
     * está a mesa?" seria afirmar uma alteração que não houve.
     */
    private AgentOutcome.Status concluding(final List<AgentOutcome.Action> actions,
                                           final List<Map<String, Object>> gateResults) {
        final var changed = actions.stream().anyMatch(a -> a.ok() && a.mutating());
        if (!changed) return AgentOutcome.Status.ANSWERED;
        if (gateResults.isEmpty()) return AgentOutcome.Status.CLEAN;
        for (final var report : gateResults) {
            final var overall = String.valueOf(report.getOrDefault("overall", "INCOMPLETE"));
            if ("FAIL".equals(overall) || "INCOMPLETE".equals(overall)) {
                return AgentOutcome.Status.GATE_FAILED;
            }
            if ("UNAVAILABLE".equals(overall)) return AgentOutcome.Status.UNAVAILABLE;
        }
        return AgentOutcome.Status.CLEAN;
    }

    private AgentOutcome finish(final AgentTrace trace, final long startedAt, final AgentOutcome.Status status,
                                final String summary, final List<AgentOutcome.Action> actions,
                                final List<Map<String, Object>> gateResults,
                                final List<Map<String, Object>> options, final String command) {
        final var text = summary == null || summary.isBlank() ? status.name() : summary;
        trace.runFinished(traceStatus(status), text, ms(startedAt),
                Map.of("status", status.name(), "actions", actions.size(),
                        "gates", gateResults.size()));
        this.state.record(command, status.name(), text);
        return new AgentOutcome(status, text, actions, gateResults,
                trace.runId(), this.state.undoAvailable(), options);
    }

    private static String traceStatus(final AgentOutcome.Status status) {
        return switch (status) {
            case CLEAN, ANSWERED -> "ok";
            case GATE_FAILED -> "error";
            case UNAVAILABLE -> "skipped";
            default -> "degraded";
        };
    }

    private static AgentOutcome.Action action(final ToolCall call, final ToolResult result, final boolean mutates) {
        return new AgentOutcome.Action(call.tool(), call.args(), result.ok(), mutates,
                result.ok() ? describe(result) : result.summary());
    }

    /** Uma linha legível do que a tool fez — vinda dos DADOS que ela devolveu. */
    private static String describe(final ToolResult result) {
        final var data = result.data();
        return switch (result.tool()) {
            case "move_object" -> data.get("label") + " movida " + data.get("distanceMm")
                    + " mm para " + data.get("direction") + " (" + data.get("partsMoved") + " peças)";
            case "undo" -> Boolean.TRUE.equals(data.get("undone"))
                    ? "alteração desfeita" : "nada para desfazer";
            case "find_object" -> data.get("count") + " objeto(s) encontrado(s)";
            case "run_gates" -> "gates: " + data.get("overall");
            default -> "ok";
        };
    }

    private LlmPlanner.AgentContext context(final String command) {
        return new LlmPlanner.AgentContext(
                command, this.state.activeProject(), this.state.activeRoom().orElse(null),
                this.state.lastReferencedObject().orElse(null), this.state.lastAction().orElse(null),
                List.copyOf(this.state.lockedObjects()), this.state.pendingEdits(),
                this.state.recentTurns(4), Map.of());
    }

    private Map<String, Object> contextMeta() {
        final var meta = new LinkedHashMap<String, Object>();
        meta.put("project", this.state.activeProject());
        meta.put("activeRoom", this.state.activeRoom().orElse(null));
        meta.put("lastReferencedObject", this.state.lastReferencedObject().orElse(null));
        meta.put("lockedObjects", List.copyOf(this.state.lockedObjects()));
        meta.put("pendingEdits", this.state.pendingEdits());
        meta.put("model", this.planner.modelName());
        meta.put("maxAttempts", this.maxAttempts);
        return meta;
    }

    private static double ms(final long since) {
        return (System.nanoTime() - since) / 1_000_000.0;
    }
}
