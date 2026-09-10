package inspector.domain;

import java.util.List;

/**
 * Um PASSO do pipeline — a caixa que o Felipe vê. Agrupa os eventos de uma mesma
 * fase para que 27 eventos virem ~8 caixas legíveis.
 *
 * <p>Tudo aqui é DERIVADO dos eventos. Nada é asserido: se o trace não disser, o
 * passo não inventa.
 */
public record PipelineStep(
        String id,
        String category,
        String componentFamily,
        String dominantComponent,
        String status,
        Double durationMs,
        boolean external,
        boolean fallbackEntry,
        long seqFrom,
        long seqTo,
        List<TraceEvent> events
) {
    public PipelineStep {
        events = List.copyOf(events);
    }

    public int eventCount() {
        return events.size();
    }
}
