package inspector.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agrupamento de eventos que compartilham {@code spanId} — a unidade de trabalho.
 * DERIVADO dos eventos, nunca asserido: se o trace não disser, o Span não inventa.
 */
public record Span(
        String spanId,
        String parentSpanId,
        String component,
        String category,
        String status,
        Double durationMs,
        List<TraceEvent> events) {
    public Span {
        events = List.copyOf(events);
    }

    /** Deriva os spans preservando a ordem de primeira aparição. */
    public static List<Span> from(final List<TraceEvent> events) {
        Map<String, List<TraceEvent>> grouped = new LinkedHashMap<>();
        for (final TraceEvent e : events) {
            final var key = e.spanId() == null ? "" : e.spanId();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<Span> spans = new ArrayList<>(grouped.size());
        for (final Map.Entry<String, List<TraceEvent>> entry : grouped.entrySet()) {
            List<TraceEvent> evs = entry.getValue();
            TraceEvent first = evs.getFirst();
            TraceEvent last = evs.getLast();
            spans.add(new Span(
                    entry.getKey().isEmpty() ? null : entry.getKey(),
                    first.parentSpanId(),
                    firstNonNull(evs, TraceEvent::component),
                    firstNonNull(evs, TraceEvent::category),
                    last.status(),
                    lastDuration(evs),
                    evs));
        }
        return List.copyOf(spans);
    }

    private static String firstNonNull(final List<TraceEvent> evs,
                                       final java.util.function.Function<TraceEvent, String> get) {
        for (final TraceEvent e : evs) {
            final var v = get.apply(e);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static Double lastDuration(final List<TraceEvent> evs) {
        Double found = null;
        for (final TraceEvent e : evs) {
            if (e.durationMs() != null) found = e.durationMs();
        }
        return found;
    }
}
