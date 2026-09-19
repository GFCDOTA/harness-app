package harness.agent.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O que o sistema REAL respondeu. Nunca um resumo do modelo.
 *
 * <p>Erro é resultado, não exceção: o agente precisa poder LER a falha e decidir o
 * próximo passo ("ambíguo, pergunta"; "travado, não insiste"), e não engasgar.
 */
public record ToolResult(
        String tool,
        boolean ok,
        Map<String, Object> data,
        String errorCode,
        String errorMessage,
        Map<String, Object> errorExtra,
        long elapsedMs) {

    public ToolResult {
        // LinkedHashMap em vez de Map.copyOf, pelo MESMO motivo de TraceEvent.meta:
        // `Map.copyOf` rejeita valor null, e null aqui é informação real — o host
        // devolve `"unique": null` justamente para dizer "achei mais de um, não
        // escolhi". Trocar isso por exceção matava o comando no lugar do dado.
        data = unmodifiable(data);
        errorExtra = unmodifiable(errorExtra);
    }

    private static Map<String, Object> unmodifiable(final Map<String, Object> source) {
        return source == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public static ToolResult success(final String tool, final Map<String, Object> data, final long elapsedMs) {
        return new ToolResult(tool, true, data, null, null, Map.of(), elapsedMs);
    }

    public static ToolResult failure(final String tool, final String code, final String message,
                                     final Map<String, Object> extra, final long elapsedMs) {
        return new ToolResult(tool, false, Map.of(), code, message, extra, elapsedMs);
    }

    /** Texto curto que volta para o modelo no próximo turno. */
    public String summary() {
        return this.ok ? "ok" : this.errorCode + ": " + this.errorMessage;
    }
}
