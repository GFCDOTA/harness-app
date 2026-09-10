package inspector.domain;

import java.util.Comparator;
import java.util.List;

/**
 * Uma execução observada. Agregado: os eventos são a fonte, todo o resto é derivado.
 *
 * <p>Ordena por {@code seq} e nunca por {@code ts} — relógio de parede não ordena
 * eventos sub-milissegundo, e o replay depende de ordem estável.
 */
public record Run(String runId, List<TraceEvent> events) {

    public Run {
        events = List.copyOf(events);
    }

    /** Ordena por seq e recusa misturar runs diferentes no mesmo agregado. */
    public static Run fromEvents(List<TraceEvent> raw) {
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("uma Run precisa de pelo menos um evento");
        }
        List<String> distinct = raw.stream().map(TraceEvent::runId).distinct().toList();
        if (distinct.size() > 1) {
            throw new IllegalArgumentException("eventos de runs diferentes no mesmo agregado: " + distinct);
        }
        List<TraceEvent> ordered = raw.stream()
                .sorted(Comparator.comparingLong(TraceEvent::seq))
                .toList();
        return new Run(distinct.getFirst(), ordered);
    }

    public int eventCount() {
        return events.size();
    }

    public List<Span> spans() {
        return Span.from(events);
    }

    /** Duração total: vem do evento terminal. Ausente = null, nunca 0.0. */
    public Double durationMs() {
        for (TraceEvent e : events.reversed()) {
            if (e.isRunTerminal() && e.durationMs() != null) return e.durationMs();
        }
        return null;
    }

    /** Status do evento terminal, ou {@code null} se a run não fechou. */
    public String terminalStatus() {
        for (TraceEvent e : events.reversed()) {
            if (e.isRunTerminal()) return e.status();
        }
        return null;
    }

    public TraceEvent first() {
        return events.getFirst();
    }
}
