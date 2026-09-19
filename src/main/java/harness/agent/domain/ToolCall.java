package harness.agent.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** O que o modelo PEDIU. Ainda não é execução — passa pelo registry antes. */
public record ToolCall(String tool, Map<String, Object> args) {

    public ToolCall {
        if (tool == null || tool.isBlank()) throw new IllegalArgumentException("chamada sem tool");
        // Os argumentos vêm do MODELO: ele consegue mandar `{"room_id": null}`, e
        // `Map.copyOf` estouraria antes do registry ter a chance de recusar com uma
        // mensagem que o modelo consegue ler e corrigir.
        args = args == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    @Override
    public String toString() {
        return this.tool + "(" + this.args + ")";
    }
}
