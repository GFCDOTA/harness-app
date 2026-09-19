package harness.agent.domain;

import java.util.List;
import java.util.Map;

/**
 * A declaração de UMA capability, como o capability host a publica.
 *
 * <p>É o contrato entre o Agent Runtime e o sistema real: o modelo só enxerga o que
 * está aqui, e só consegue pedir o que está aqui. Não existe caminho de "shell
 * livre" — se a capability não está registrada, ela não acontece.
 *
 * <p>{@code risk} e {@code undoable} não são decoração: é com eles que o runtime
 * decide o que pode rodar sozinho e o que precisa de confirmação humana.
 */
public record ToolSpec(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Risk risk,
        boolean undoable,
        boolean mutates,
        List<String> requires,
        int timeoutSec) {

    public ToolSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool sem nome");
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        requires = requires == null ? List.of() : List.copyOf(requires);
        if (risk == null) risk = Risk.LOW;
    }

    /** {@code true} quando o runtime NÃO pode executar sem um "pode" humano. */
    public boolean needsConfirmation() {
        return this.risk == Risk.HIGH;
    }
}
