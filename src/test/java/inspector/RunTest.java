package inspector;

import inspector.domain.Run;
import inspector.domain.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunTest {

    private static TraceEvent ev(long seq, String name, String status, Double dur, String runId) {
        return new TraceEvent(seq, runId, "s01", null, "2026-01-02T03:00:00.000Z",
                dur, "comp", "RAG", status, name, Map.of());
    }

    @Test
    void ordenaPorSeqEnaoPelaOrdemDeEntrada() {
        Run run = Run.fromEvents(List.of(
                ev(10, "run.finished", "ok", 100.0, "r1"),
                ev(1, "run.started", "started", null, "r1"),
                ev(5, "llm.finished", "ok", 50.0, "r1")));
        assertEquals(List.of(1L, 5L, 10L), run.events().stream().map(TraceEvent::seq).toList());
    }

    @Test
    void recusaMisturarRunsDiferentesNoMesmoAgregado() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Run.fromEvents(List.of(
                ev(1, "run.started", "started", null, "r1"),
                ev(2, "llm.finished", "ok", 1.0, "r2"))));
        assertTrue(ex.getMessage().contains("runs diferentes"));
    }

    @Test
    void recusaRunSemEvento() {
        assertThrows(IllegalArgumentException.class, () -> Run.fromEvents(List.of()));
    }

    @Test
    void duracaoVemDoEventoTerminal() {
        Run run = Run.fromEvents(List.of(
                ev(1, "run.started", "started", null, "r1"),
                ev(2, "run.finished", "ok", 16751.7, "r1")));
        assertEquals(16751.7, run.durationMs());
        assertEquals("ok", run.terminalStatus());
    }

    @Test
    void semEventoTerminalDuracaoEhNulaNuncaZero() {
        Run run = Run.fromEvents(List.of(ev(1, "run.started", "started", null, "r1")));
        assertNull(run.durationMs(), "duração ausente virando 0.0 mentiria sobre a run");
        assertNull(run.terminalStatus());
    }

    @Test
    void listaDeEventosEhImutavel() {
        Run run = Run.fromEvents(List.of(ev(1, "run.started", "started", null, "r1")));
        assertThrows(UnsupportedOperationException.class,
                () -> run.events().add(ev(2, "x", "ok", null, "r1")));
    }
}
