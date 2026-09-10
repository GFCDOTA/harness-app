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
    void oRotuloDizONDE_aCoisaRodaEnaoOhVagoExterno() throws Exception {
        for (JsonNode b : project().get("boxes")) {
            String comp = b.get("component").asText();
            String rotulo = b.get("kindLabel").asText();
            assertNotEquals("HTTP externo", rotulo,
                    "esse rotulo induzia a pensar em API na internet");
            if (comp.startsWith("ollama.deepseek")) {
                assertEquals("LLM local", rotulo, comp);
            } else if (comp.startsWith("ollama.")) {
                assertEquals("HTTP local", rotulo, comp);
            } else if (comp.startsWith("qdrant.")) {
                assertEquals("Docker local", rotulo, comp);
            } else {
                assertEquals("código local", rotulo, comp);
            }
        }
    }

    @Test
    void nenhumaChamadaDestaExecucaoSaiDaMaquina() throws Exception {
        for (JsonNode b : project().get("boxes")) {
            assertNotEquals("HTTP internet", b.get("kindLabel").asText(),
                    "Ollama e Qdrant rodam nesta maquina: " + b.get("component").asText());
        }
    }

    @Test
    void oNoTrazONomeHumanoEondeRoda() throws Exception {
        for (JsonNode n : project().get("pipeline").get("nodes")) {
            JsonNode perfil = n.get("profile");
            assertNotNull(perfil, "todo no precisa de perfil");
            assertFalse(perfil.get("humanName").asText().isBlank());
            assertFalse(perfil.get("kindLabel").asText().isBlank());
        }
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
