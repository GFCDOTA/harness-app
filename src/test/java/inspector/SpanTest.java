package inspector;

import inspector.domain.Span;
import inspector.domain.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpanTest {

    private static TraceEvent ev(long seq, String spanId, String parent, String comp,
                                 String cat, String status, Double dur) {
        return new TraceEvent(seq, "r1", spanId, parent, null, dur, comp, cat, status,
                "evt" + seq, Map.of());
    }

    @Test
    void agrupaPorSpanIdPreservandoOrdemDePrimeiraAparicao() {
        List<Span> spans = Span.from(List.of(
                ev(1, "sA", null, "compA", "RAG", "started", null),
                ev(2, "sB", "sA", "compB", "LLM", "ok", 5.0),
                ev(3, "sA", null, "compA", "RAG", "ok", 99.0)));
        assertEquals(List.of("sA", "sB"), spans.stream().map(Span::spanId).toList());
    }

    @Test
    void statusEhDoUltimoEventoDoSpan() {
        List<Span> spans = Span.from(List.of(
                ev(1, "sA", null, "c", "RAG", "started", null),
                ev(2, "sA", null, "c", "RAG", "failed", 40.0)));
        assertEquals("failed", spans.getFirst().status());
        assertEquals(40.0, spans.getFirst().durationMs());
    }

    @Test
    void componenteVemDoPrimeiroEventoQueOInforma() {
        List<Span> spans = Span.from(List.of(
                ev(1, "sA", null, null, null, "started", null),
                ev(2, "sA", null, "qdrant.rag_chunks", "RAG", "ok", 1.0)));
        assertEquals("qdrant.rag_chunks", spans.getFirst().component());
        assertEquals("RAG", spans.getFirst().category());
    }

    @Test
    void eventoSemSpanIdNaoInventaSpan() {
        List<Span> spans = Span.from(List.of(ev(1, null, null, "c", "RAG", "ok", null)));
        assertEquals(1, spans.size());
        assertNull(spans.getFirst().spanId());
    }

    @Test
    void duracaoAusenteEmTodosOsEventosFicaNula() {
        List<Span> spans = Span.from(List.of(ev(1, "sA", null, "c", "RAG", "started", null)));
        assertNull(spans.getFirst().durationMs());
    }
}
