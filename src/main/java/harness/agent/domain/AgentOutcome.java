package harness.agent.domain;

import java.util.List;
import java.util.Map;

/**
 * O desfecho de UM comando. É isto que a UI mostra e o que o handoff registra.
 *
 * <p>A regra que o formato impõe (missão §33): "o Qwen respondeu" não é sucesso.
 * {@code status} só é {@link Status#CLEAN} quando a alteração aconteceu de verdade
 * E os gates rodaram E não reprovaram.
 */
public record AgentOutcome(
        Status status,
        String summary,
        List<Action> actions,
        List<Map<String, Object>> gateResults,
        String traceId,
        boolean undoAvailable,
        List<Map<String, Object>> options) {

    public enum Status {
        /** Executou e os gates aprovaram (PASS ou WARN). */
        CLEAN,
        /** Executou, mas um gate determinístico reprovou. */
        GATE_FAILED,
        /** Executou, mas o Harness nao conseguiu provar o efeito esperado. */
        UNVERIFIED,
        /** Nada mudou: era leitura, pergunta ou consulta. */
        ANSWERED,
        /** Precisa de uma decisão do Felipe (ambiguidade, risco HIGH, ou sem saída). */
        NEEDS_FELIPE,
        /** Uma dependência necessária está fora do ar. */
        UNAVAILABLE,
        /** O agente gastou as tentativas sem chegar a um desfecho. */
        EXHAUSTED
    }

    /**
     * Uma coisa que realmente aconteceu no sistema.
     *
     * <p>{@code mutating} vem do {@link ToolSpec} da tool, não do fato de ela ter
     * respondido ok: {@code find_object} devolvendo sucesso é uma LEITURA, e tratar
     * isso como alteração fazia uma pergunta ("onde está a mesa?") terminar como
     * CLEAN — dizendo que mexeu no projeto quando não mexeu.
     */
    public record Action(String tool, Map<String, Object> args, boolean ok,
                         boolean mutating, String detail) {

        public Action {
            // mesmo motivo de ToolCall.args: argumento do modelo pode vir null
            args = args == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(args));
        }
    }

    public AgentOutcome {
        actions = actions == null ? List.of() : List.copyOf(actions);
        gateResults = gateResults == null ? List.of() : List.copyOf(gateResults);
        options = options == null ? List.of() : List.copyOf(options);
    }

    public boolean changedSystem() {
        return this.actions.stream().anyMatch(action -> action.ok() && action.mutating());
    }
}
