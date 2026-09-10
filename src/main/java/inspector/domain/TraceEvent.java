package inspector.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Um evento do trace, exatamente como o envelope v1 do {@code core/observability}.
 *
 * <p>Value object imutável e SEM anotação de framework: quem sabe ler JSON é o
 * adapter em {@code inspector.source}, não o domínio.
 *
 * <p>{@code category} é String de propósito. O catálogo de categorias é FECHADO no
 * lado Python (que é o dono da taxonomia); um enum aqui transformaria "categoria
 * nova" em exceção em vez de dado, e o Inspector existe para observar, não para
 * recusar o que observou.
 */
public record TraceEvent(
        long seq,
        String runId,
        String spanId,
        String parentSpanId,
        String ts,
        Double durationMs,
        String component,
        String category,
        String status,
        String name,
        Map<String, Object> meta
) {
    public TraceEvent {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId é obrigatório no envelope");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name é obrigatório no envelope");
        }
        if (seq < 0) {
            throw new IllegalArgumentException("seq não pode ser negativo: " + seq);
        }
        // LinkedHashMap em vez de Map.copyOf: meta pode conter valor null
        // (ex. counts ausente), e Map.copyOf rejeita null.
        meta = meta == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    /** O evento que fecha a run. */
    public boolean isRunTerminal() {
        return "run.finished".equals(name);
    }
}
