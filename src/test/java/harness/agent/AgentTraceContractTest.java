package harness.agent;

import harness.agent.domain.AgentRuntime;
import harness.agent.domain.AgentState;
import harness.agent.domain.AgentTrace;
import harness.agent.domain.PlannerDecision;
import harness.agent.domain.ToolCall;
import harness.agent.domain.ToolRegistry;
import harness.agent.source.JsonlTraceRecorder;
import inspector.domain.Run;
import inspector.projection.TraceProjection;
import inspector.source.JsonlReplayTraceSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CONTRACT TEST entre os dois planos do Harness.
 *
 * <p>O control plane GRAVA e o Inspector LÊ — e eles só se falam pelo envelope v1.
 * Esta é a costura que garante que um comando do Felipe apareça na Pipeline View
 * sem código de visualização novo, e que quebre ALTO se alguém mudar o formato de
 * um lado só.
 *
 * <p>O trace do agente passa por todo o caminho real: {@code JsonlTraceRecorder}
 * escreve, {@code JsonlReplayTraceSource} lê de volta, {@code Run} monta o domínio e
 * {@code TraceProjection} projeta. Nenhum dublê de arquivo.
 */
class AgentTraceContractTest {

    private Path runAgent(final Path dir, final String runId) {
        final var host = FakeCapabilityHost.standard();
        final var registry = new ToolRegistry(host.describeTools(), host.unsupported());
        final var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(
                        new ToolCall("find_object", Map.of("query", "escrivaninha")))),
                PlannerDecision.callTools(List.of(new ToolCall("move_object",
                        Map.of("object_id", "suite_01.escrivaninha",
                                "direction", "left", "distance_mm", 300)))),
                PlannerDecision.finalAnswer("movida"));
        final var recorder = new JsonlTraceRecorder(dir, runId);
        final var trace = new AgentTrace(runId, recorder);
        new AgentRuntime(host, planner, registry, new AgentState("planta_74"), 4, false)
                .execute("move a escrivaninha 30 cm para a esquerda", trace);
        return recorder.file();
    }

    @Test
    void oTraceDoAgenteEhLidoPeloReplayDoInspector(@TempDir final Path dir) {
        final var file = runAgent(dir, "cmd_contrato");

        final var events = new JsonlReplayTraceSource(file).readAll();

        assertFalse(events.isEmpty(), "o recorder não escreveu nada");
        assertTrue(events.stream().allMatch(e -> "cmd_contrato".equals(e.runId())));
        assertEquals(List.of("run.started", "agent.plan", "agent.plan", "tool.invoke",
                        "agent.plan", "agent.plan", "tool.invoke", "gate.run",
                        "agent.plan", "agent.plan", "run.finished"),
                events.stream().map(e -> e.name()).toList());
    }

    @Test
    void oInspectorMONTAaRunEaPROJETAsemConhecerOagente(@TempDir final Path dir) {
        final var file = runAgent(dir, "cmd_projecao");

        final var collected = new ArrayList<inspector.domain.TraceEvent>();
        new JsonlReplayTraceSource(file).stream(collected::add);
        final var run = Run.fromEvents(collected);
        final var json = new TraceProjection().toJson(run, "teste");

        assertEquals("cmd_projecao", run.runId());
        assertTrue(json.contains("capability.move_object"),
                "a chamada de tool tem que chegar na projeção");
        assertTrue(json.contains("gate.deterministic"),
                "o gate tem que chegar na projeção");
    }

    @Test
    void aChamadaDeToolEhFILHAdoPlanoTambemDEPOISdoIdaEvoltaPeloDisco(@TempDir final Path dir) {
        // A parentalidade e' o que faz o grafo dizer "este plano gerou esta chamada".
        // Se ela se perder na serializacao, a Pipeline View volta a ser uma fila plana.
        final var file = runAgent(dir, "cmd_pais");

        final var events = new JsonlReplayTraceSource(file).readAll();
        final var plan = events.stream()
                .filter(e -> "agent.plan".equals(e.name()) && "ok".equals(e.status()))
                .findFirst().orElseThrow();
        final var tool = events.stream()
                .filter(e -> "tool.invoke".equals(e.name()))
                .findFirst().orElseThrow();

        assertEquals(plan.spanId(), tool.parentSpanId());
    }

    @Test
    void oDesfechoRealVIAJAnoEnvelope(@TempDir final Path dir) {
        final var file = runAgent(dir, "cmd_desfecho");

        final var finished = new JsonlReplayTraceSource(file).readAll().stream()
                .filter(e -> "run.finished".equals(e.name())).findFirst().orElseThrow();

        assertEquals("CLEAN", finished.meta().get("status"));
        assertEquals("ok", finished.status());
        assertTrue(finished.meta().containsKey("summary"));
    }

    @Test
    void ofalhaDeEscritaNAOderrubaOcomando(@TempDir final Path dir) throws Exception {
        // Perder o trace e' ruim; perder a OPERACAO por causa do trace e' pior.
        final var blocked = dir.resolve("arquivo-no-lugar-do-diretorio");
        java.nio.file.Files.writeString(blocked, "nao sou diretorio");

        final var recorder = new JsonlTraceRecorder(blocked, "cmd_x");
        final var host = FakeCapabilityHost.standard();
        final var registry = new ToolRegistry(host.describeTools(), host.unsupported());
        final var planner = new ScriptedPlanner("q", PlannerDecision.finalAnswer("ok"));

        final var outcome = new AgentRuntime(host, planner, registry,
                new AgentState("p"), 2, false)
                .execute("qualquer coisa", new AgentTrace("cmd_x", recorder));

        assertEquals("ok", outcome.summary());
    }
}
