package harness.agent.domain;

import java.util.List;
import java.util.Map;

/**
 * PORT — o modelo local que interpreta intenção e escolhe capabilities.
 *
 * <p>A hierarquia que este port protege (missão §49): o Harness é o sistema; o Qwen
 * é UM componente dele. O planner não executa nada, não afirma que algo é válido e
 * não é fonte de verdade de geometria. Ele traduz linguagem em chamadas tipadas e
 * lê resultados. Quem executa é o {@link CapabilityHost}; quem julga são os gates.
 */
public interface LlmPlanner {

    /**
     * @param context  retrato do estado + intenção do usuário
     * @param tools    o que existe para ser chamado (schemas do registry)
     * @param history  o que já foi chamado NESTE comando, com os resultados reais
     */
    PlannerDecision plan(AgentContext context, List<ToolSpec> tools, List<Step> history);

    /** {@code true} quando o modelo está no ar. */
    boolean isAvailable();

    /** Nome do modelo efetivamente em uso, para o trace não mentir. */
    String modelName();

    /** Um passo já executado neste comando: o que foi pedido, o que voltou. */
    record Step(ToolCall call, ToolResult result) {
    }

    /** O contexto mínimo necessário — não o projeto inteiro (missão §41). */
    record AgentContext(
            String command,
            String project,
            String activeRoom,
            String lastReferencedObject,
            String lastAction,
            List<String> lockedObjects,
            int pendingEdits,
            List<AgentState.Turn> recentTurns,
            Map<String, Object> sceneSummary) {
        public AgentContext {
            lockedObjects = lockedObjects == null ? List.of() : List.copyOf(lockedObjects);
            recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
            sceneSummary = sceneSummary == null ? Map.of() : Map.copyOf(sceneSummary);
        }
    }
}
