package inspector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import inspector.domain.Run;
import inspector.domain.TraceEvent;
import inspector.projection.TraceProjection;
import inspector.source.JsonlReplayTraceSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraceProjectionTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Run fixtureRun() {
        Path f = Paths.get("src", "test", "resources", "traces", "sample_run.jsonl");
        List<TraceEvent> evs = new ArrayList<>();
        new JsonlReplayTraceSource(f).stream(evs::add);
        return Run.fromEvents(evs);
    }

    private static JsonNode project() throws Exception {
        return M.readTree(new TraceProjection()
                .toJson(fixtureRun(), "jsonl-replay: sample_run.jsonl"));
    }

    @Test
    void oPayloadTrazOResumoDaRun() throws Exception {
        JsonNode j = project();
        assertEquals("run_TEST_0001", j.get("runId").asText());
        assertEquals(9, j.get("eventCount").asInt());
        assertEquals(16810.0, j.get("durationMs").asDouble());
        assertEquals("ok", j.get("terminalStatus").asText());
        assertEquals(9, j.get("boxes").size());
    }

    @Test
    void marcaChamadaHttpExternaPeloPrefixoDoComponent() throws Exception {
        for (JsonNode b : project().get("boxes")) {
            String comp = b.get("component").asText();
            boolean external = b.get("external").asBoolean();
            if (comp.startsWith("ollama.") || comp.startsWith("qdrant.")) {
                assertTrue(external, comp + " e servico externo e deveria estar marcado");
            } else {
                assertFalse(external, comp + " e codigo local e nao deveria estar marcado");
            }
        }
    }

    @Test
    void contaExatamenteAsQuatroChamadasExternasDaFixture() throws Exception {
        long ext = 0;
        for (JsonNode b : project().get("boxes")) {
            if (b.get("external").asBoolean()) ext++;
        }
        assertEquals(4, ext,
                "3 do ollama (embed started + embed finished + llm) + 1 do qdrant. "
                        + "O LLM tambem e chamada HTTP externa: e justamente o que a "
                        + "caixa precisa deixar obvio.");
    }

    @Test
    void tPlusMsEhOffsetDoInicioDaRun() throws Exception {
        JsonNode boxes = project().get("boxes");
        assertEquals(0L, boxes.get(0).get("tPlusMs").asLong(), "o primeiro evento e a origem");
        assertEquals(16810L, boxes.get(boxes.size() - 1).get("tPlusMs").asLong());
    }

    @Test
    void duracaoAusenteViaJsonNuloEnaoZero() throws Exception {
        JsonNode degradado = null;
        for (JsonNode b : project().get("boxes")) {
            if (b.get("name").asText().equals("rag.degraded")) degradado = b;
        }
        assertNotNull(degradado);
        assertTrue(degradado.get("durationMs").isNull(),
                "0.0 faria a UI desenhar instantaneo onde nao houve medida");
    }

    @Test
    void detalheTrazAsChavesDeMetaQueExplicamADegradacao() throws Exception {
        for (JsonNode b : project().get("boxes")) {
            if (b.get("name").asText().equals("rag.degraded")) {
                String d = b.get("detail").asText();
                assertTrue(d.contains("backendRequested=embed"), d);
                assertTrue(d.contains("backendActual=faceted"), d);
                assertTrue(d.contains("fallbackReason=InfraUnavailable"), d);
                assertFalse(d.contains("counts"),
                        "chave de meta com valor nulo nao vira ruido: " + d);
                return;
            }
        }
        fail("evento rag.degraded nao apareceu na projecao");
    }

    @Test
    void oPayloadNaoVazaObjetoJavaSoJson() throws Exception {
        String json = new TraceProjection().toJson(fixtureRun(), "origem");
        assertFalse(json.contains("inspector.domain"), "nome de classe Java vazou pro payload");
        assertFalse(json.contains("@"), "referencia de objeto Java vazou pro payload");
    }
}
