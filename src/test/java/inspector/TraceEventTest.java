package inspector;

import inspector.domain.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceEventTest {

    private static TraceEvent valid(Map<String, Object> meta) {
        return new TraceEvent(1L, "run_X", "s01", null, "2026-01-02T03:00:00.000Z",
                null, "c", "RAG", "ok", "rag.query.started", meta);
    }

    @Test
    void rejeitaRunIdVazio() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new TraceEvent(
                1L, "  ", "s", null, null, null, null, null, null, "n", Map.of()));
        assertTrue(ex.getMessage().contains("runId"));
    }

    @Test
    void rejeitaNameVazio() {
        assertThrows(IllegalArgumentException.class, () -> new TraceEvent(
                1L, "run_X", "s", null, null, null, null, null, null, " ", Map.of()));
    }

    @Test
    void rejeitaSeqNegativo() {
        assertThrows(IllegalArgumentException.class, () -> new TraceEvent(
                -1L, "run_X", "s", null, null, null, null, null, null, "n", Map.of()));
    }

    @Test
    void metaNulaViraMapaVazioEmVezDeNPE() {
        assertEquals(Map.of(), valid(null).meta());
    }

    @Test
    void metaAceitaValorNuloPorqueOTraceRealTemCountsNulo() {
        Map<String, Object> m = new HashMap<>();
        m.put("counts", null);
        TraceEvent e = valid(m);
        assertTrue(e.meta().containsKey("counts"));
        assertNull(e.meta().get("counts"));
    }

    @Test
    void metaFicaImutavelDepoisDeConstruido() {
        Map<String, Object> mutavel = new HashMap<>();
        mutavel.put("k", "v");
        TraceEvent e = valid(mutavel);
        mutavel.put("intruso", "x");
        assertEquals(1, e.meta().size(), "a cópia defensiva não pegou a mutação externa");
        assertThrows(UnsupportedOperationException.class, () -> e.meta().put("z", "1"));
    }

    @Test
    void reconheceOEventoTerminalDaRun() {
        assertFalse(valid(Map.of()).isRunTerminal());
        TraceEvent fim = new TraceEvent(9L, "run_X", "s01", null, null, 10.0,
                "c", "OBSERVABILITY", "ok", "run.finished", Map.of());
        assertTrue(fim.isRunTerminal());
    }
}
