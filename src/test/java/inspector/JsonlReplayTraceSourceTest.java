package inspector;

import inspector.domain.Run;
import inspector.domain.TraceEvent;
import inspector.source.JsonlReplayTraceSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JsonlReplayTraceSourceTest {

    private static Path fixture() {
        return Paths.get("src", "test", "resources", "traces", "sample_run.jsonl");
    }

    private static List<TraceEvent> collect(JsonlReplayTraceSource src) {
        List<TraceEvent> out = new ArrayList<>();
        src.stream(out::add);
        return out;
    }

    @Test
    void leTodosOsEventosDaFixture() {
        assertEquals(9, collect(new JsonlReplayTraceSource(fixture())).size());
    }

    @Test
    void entregaEmOrdemDeSeqMesmoComLacunaEArquivoDesordenado() {
        List<Long> seqs = collect(new JsonlReplayTraceSource(fixture()))
                .stream().map(TraceEvent::seq).toList();
        assertEquals(List.of(1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), seqs,
                "seq 2 nao existe na fixture: a lacuna e preservada, nao preenchida");
    }

    @Test
    void preservaOsCamposDoEnvelope() {
        TraceEvent degradado = collect(new JsonlReplayTraceSource(fixture()))
                .stream().filter(e -> e.name().equals("rag.degraded")).findFirst().orElseThrow();
        assertEquals("degraded", degradado.status());
        assertEquals("RAG", degradado.category());
        assertEquals("InfraUnavailable", degradado.meta().get("fallbackReason"));
        assertEquals(Boolean.TRUE, degradado.meta().get("fallbackTriggered"));
        assertNull(degradado.durationMs());
    }

    @Test
    void ignoraLinhaEmBrancoMasFalhaAltoEmLinhaInvalida(@TempDir Path dir) throws Exception {
        Path ok = dir.resolve("ok.jsonl");
        Files.writeString(ok,
                "{\"runId\":\"r1\",\"seq\":1,\"name\":\"run.started\"}\n"
                        + "\n"
                        + "{\"runId\":\"r1\",\"seq\":2,\"name\":\"run.finished\"}\n",
                StandardCharsets.UTF_8);
        assertEquals(2, collect(new JsonlReplayTraceSource(ok)).size());

        Path ruim = dir.resolve("ruim.jsonl");
        Files.writeString(ruim, "{\"runId\":\"r1\",\"seq\":1,\"name\":\"a\"}\nnao-e-json\n",
                StandardCharsets.UTF_8);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> collect(new JsonlReplayTraceSource(ruim)));
        assertTrue(ex.getMessage().contains("linha 2"),
                "o erro precisa dizer QUAL linha: " + ex.getMessage());
    }

    @Test
    void describeIdentificaAOrigemParaAUi() {
        assertTrue(new JsonlReplayTraceSource(fixture()).describe().contains("sample_run.jsonl"));
    }

    @Test
    void newestInEscolheOArquivoMaisRecente(@TempDir Path dir) throws Exception {
        Path velho = dir.resolve("velho.jsonl");
        Path novo = dir.resolve("novo.jsonl");
        Files.writeString(velho, "{\"runId\":\"r1\",\"seq\":1,\"name\":\"a\"}\n", StandardCharsets.UTF_8);
        Files.writeString(novo, "{\"runId\":\"r2\",\"seq\":1,\"name\":\"a\"}\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(velho, FileTime.fromMillis(1000000L));
        Files.setLastModifiedTime(novo, FileTime.fromMillis(2000000L));
        assertEquals("novo.jsonl",
                JsonlReplayTraceSource.newestIn(dir).file().getFileName().toString());
    }

    @Test
    void traceRealDeBanheiroTem27EventosQuandoPresente() {
        Path real = Paths.get("..", ".ai_bridge", "traces", "run_20260827T021348Z_banho.jsonl");
        assumeTrue(Files.exists(real), "trace real ausente (gitignored): teste pulado");
        Run run = Run.fromEvents(collect(new JsonlReplayTraceSource(real)));
        assertEquals(27, run.eventCount());
        assertEquals("run_20260827T021348Z_banho", run.runId());
        assertNotNull(run.durationMs());
    }
}
