package harness.agent.domain;

import java.util.List;

/**
 * O que o modelo decidiu num turno: chamar capabilities, ou parar e responder.
 *
 * <p>{@code unavailable} é um desfecho de primeira classe: Ollama fora do ar não
 * pode virar exceção genérica nem resposta inventada — o Harness continua
 * funcionando para o caminho determinístico (missão §42) e diz o que faltou.
 */
public record PlannerDecision(Kind kind, List<ToolCall> calls, String message) {

    public enum Kind {
        /** Executar estas chamadas e voltar para o modelo com os resultados. */
        CALL_TOOLS,
        /** O modelo considera o objetivo resolvido; {@code message} é o resumo. */
        FINAL,
        /** O modelo não conseguiu decidir e pede desambiguação ao humano. */
        NEEDS_HUMAN,
        /** O planner não está disponível (modelo fora do ar, resposta ilegível). */
        UNAVAILABLE
    }

    public PlannerDecision {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    public static PlannerDecision callTools(final List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            throw new IllegalArgumentException("CALL_TOOLS sem nenhuma chamada");
        }
        return new PlannerDecision(Kind.CALL_TOOLS, calls, null);
    }

    public static PlannerDecision finalAnswer(final String message) {
        return new PlannerDecision(Kind.FINAL, List.of(), message);
    }

    public static PlannerDecision needsHuman(final String message) {
        return new PlannerDecision(Kind.NEEDS_HUMAN, List.of(), message);
    }

    public static PlannerDecision unavailable(final String why) {
        return new PlannerDecision(Kind.UNAVAILABLE, List.of(), why);
    }
}
