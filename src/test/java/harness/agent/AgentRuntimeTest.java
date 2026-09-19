package harness.agent;

import harness.agent.domain.AgentOutcome;
import harness.agent.domain.AgentRuntime;
import harness.agent.domain.AgentState;
import harness.agent.domain.AgentTrace;
import harness.agent.domain.PlannerDecision;
import harness.agent.domain.ToolCall;
import harness.agent.domain.ToolRegistry;
import harness.agent.domain.ToolResult;
import harness.agent.domain.TraceRecorder;
import inspector.domain.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O laço do agente sob teste — inclusive quando tudo dá errado.
 *
 * <p>Nenhum destes precisa de Ollama, Python ou SketchUp. É o ponto: a governança
 * do Harness é código determinístico e tem que ser testável como tal.
 */
class AgentRuntimeTest {

    /** Recorder que guarda os eventos para a asserção ver o trace estruturado. */
    private static final class Capturing implements TraceRecorder {
        final List<TraceEvent> events = new ArrayList<>();

        @Override
        public void record(final TraceEvent event) {
            events.add(event);
        }

        List<String> names() {
            return events.stream().map(TraceEvent::name).toList();
        }
    }

    private AgentRuntime runtime(final FakeCapabilityHost host, final ScriptedPlanner planner,
                                 final AgentState state, final int maxAttempts) {
        ToolRegistry registry = new ToolRegistry(host.describeTools(), host.unsupported());
        return new AgentRuntime(host, planner, registry, state, maxAttempts, false);
    }

    private static ToolCall move(final String id, final String dir, final double mm) {
        return new ToolCall("move_object",
                Map.of("object_id", id, "direction", dir, "distance_mm", mm));
    }

    // -- caminho feliz ------------------------------------------------------
    @Test
    void comandoQueMoveEValidaTerminaClean() {
        var host = FakeCapabilityHost.standard();
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(
                        new ToolCall("find_object", Map.of("query", "escrivaninha")))),
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.finalAnswer("Escrivaninha movida 300 mm para a esquerda."));
        var state = new AgentState("planta_74");
        var trace = new AgentTrace("run_test", TraceRecorder.NOOP);

        AgentOutcome out = runtime(host, planner, state, 4).execute("move a escrivaninha 30 cm para a esquerda", trace);

        assertEquals(AgentOutcome.Status.CLEAN, out.status());
        assertTrue(out.changedSystem());
        assertTrue(out.undoAvailable());
        assertEquals(List.of("find_object", "move_object", "run_gates"), host.toolNamesCalled());
    }

    @Test
    void oGateRodaSOZINHODepoisDeUmaAlteracaoGeometrica() {
        var host = FakeCapabilityHost.standard();
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.finalAnswer("pronto"));

        runtime(host, planner, new AgentState("p"), 3).execute("move 30 cm para a esquerda", new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("run_gates"),
                "validar não pode ser escolha do modelo");
    }

    @Test
    void gateReprovandoNAOviraClean() {
        var host = FakeCapabilityHost.standard();
        host.replace("run_gates", args -> ToolResult.success("run_gates",
                Map.of("roomId", "r000", "overall", "FAIL", "clean", false,
                        "findings", List.of(Map.of("type", "furniture_overlap",
                                "route", "DETERMINISTIC_AUTOFIX"))), 30));
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 900))),
                PlannerDecision.finalAnswer("movi a escrivaninha, ficou ótimo"));

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move 30 cm para a esquerda", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.GATE_FAILED, out.status(),
                "o modelo dizer que ficou bom não vale nada contra o gate");
        assertEquals(1, out.gateResults().size());
    }

    @Test
    void leituraPuraTerminaComoRespondidoSemMudarNada() {
        var host = FakeCapabilityHost.standard();
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(new ToolCall("find_object", Map.of("query", "mesa")))),
                PlannerDecision.finalAnswer("achei uma"));

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("onde está a mesa?", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.ANSWERED, out.status());
        assertFalse(out.undoAvailable());
    }

    // -- governança ---------------------------------------------------------
    @Test
    void toolInexistenteNaoViraComandoEoModeloRecebeOerro() {
        var host = FakeCapabilityHost.standard();
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(new ToolCall("shell",
                        Map.of("cmd", "del /f /s /q C:")))),
                PlannerDecision.finalAnswer("não consegui"));

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("apaga tudo", new AgentTrace("r", TraceRecorder.NOOP));

        assertFalse(host.toolNamesCalled().contains("shell"));
        assertEquals(AgentOutcome.Status.ANSWERED, out.status());
        assertTrue(out.actions().get(0).detail().contains("UNKNOWN_TOOL"));
    }

    @Test
    void riscoHighParaOfluxoEpedeConfirmacao() {
        var host = FakeCapabilityHost.standard();
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(new ToolCall("delete_object",
                        Map.of("object_id", "suite_01.cama")))));

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("apaga a cama", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("delete_object"),
                "risco HIGH não pode ter sido executado");
        assertEquals(1, out.options().size(), "a UI precisa saber o que confirmar");
    }

    @Test
    void riscoHighRodaQuandoAPoliticaJAautorizou() {
        var host = FakeCapabilityHost.standard();
        var registry = new ToolRegistry(host.describeTools(), host.unsupported());
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(new ToolCall("delete_object",
                        Map.of("object_id", "x")))),
                PlannerDecision.finalAnswer("apagado"));
        var runtime = new AgentRuntime(host, planner, registry, new AgentState("p"), 3, true);

        runtime.execute("apaga", new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("delete_object"));
    }

    @Test
    void tetoDeTentativasImpedeLacoInfinito() {
        var host = FakeCapabilityHost.standard();
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(new ToolCall("find_object", Map.of("query", "a")))),
                PlannerDecision.callTools(List.of(new ToolCall("find_object", Map.of("query", "b")))),
                PlannerDecision.callTools(List.of(new ToolCall("find_object", Map.of("query", "c")))),
                PlannerDecision.callTools(List.of(new ToolCall("find_object", Map.of("query", "d")))),
                PlannerDecision.callTools(List.of(new ToolCall("find_object", Map.of("query", "e")))));

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("organiza", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.EXHAUSTED, out.status());
        assertEquals(3, host.toolNamesCalled().size());
    }

    // -- resiliência --------------------------------------------------------
    @Test
    void hostForaDoArNaoTentaNadaEdizOqueFalta() {
        var host = FakeCapabilityHost.standard();
        host.kill();
        var planner = new ScriptedPlanner("qwen-teste");

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move a mesa 30 cm para a esquerda", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.UNAVAILABLE, out.status());
        assertTrue(out.summary().contains("capability host"));
    }

    @Test
    void modeloForaDoArEdesfechoDeclaradoNaoErroGenerico() {
        var host = FakeCapabilityHost.standard();
        var planner = new ScriptedPlanner("qwen-teste");
        planner.unavailable();

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move a mesa 30 cm para a esquerda", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.UNAVAILABLE, out.status());
        assertTrue(out.summary().contains("Ollama"));
        assertTrue(host.calls.isEmpty());
    }

    @Test
    void respostaMalformadaDoModeloNaoViraAcao() {
        var host = FakeCapabilityHost.standard();
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.unavailable("o modelo devolveu JSON quebrado"));

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move 30 cm para a esquerda", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.UNAVAILABLE, out.status());
        assertTrue(host.calls.isEmpty());
    }

    @Test
    void plannerQueExplodeViraIndisponivelEmVezDeDerrubarOcomando() {
        var host = FakeCapabilityHost.standard();
        var planner = new harness.agent.domain.LlmPlanner() {
            @Override
            public PlannerDecision plan(final AgentContext c, final List<harness.agent.domain.ToolSpec> t,
                                        final List<Step> h) {
                throw new IllegalStateException("conexão caiu no meio");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String modelName() {
                return "qwen-teste";
            }
        };
        var registry = new ToolRegistry(host.describeTools(), host.unsupported());

        AgentOutcome out = new AgentRuntime(host, planner, registry, new AgentState("p"), 3, false)
                .execute("move 30 cm para a esquerda", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.UNAVAILABLE, out.status());
        assertTrue(out.summary().contains("conexão caiu"));
    }

    @Test
    void toolQueFalhaViraDadoParaOmodeloEnaoInterrompe() {
        var host = FakeCapabilityHost.standard();
        host.replace("move_object", args -> ToolResult.failure("move_object", "AMBIGUOUS",
                "'mesa' casa com mais de um objeto",
                Map.of("candidates", List.of(Map.of("id", "a"), Map.of("id", "b"))), 2));
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(move("mesa", "left", 300))),
                PlannerDecision.needsHuman("Qual mesa? 1) sala 2) cozinha"));

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move a mesa 30 cm para a esquerda", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertTrue(out.summary().contains("Qual mesa"));
    }

    @Test
    void gateIndisponivelNaoViraClean() {
        var host = FakeCapabilityHost.standard();
        host.replace("run_gates", args -> ToolResult.success("run_gates",
                Map.of("roomId", "r000", "overall", "UNAVAILABLE"), 5));
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.finalAnswer("movido"));

        AgentOutcome out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move 30 cm para a esquerda", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.UNAVAILABLE, out.status());
    }

    @Test
    void falhaDeVerificacaoNaoViraCleanNemChangedSystem() {
        final var host = FakeCapabilityHost.standard();
        host.replace("move_object", args -> ToolResult.success("move_object",
                Map.of("objectId", "suite_01.escrivaninha",
                        "roomId", "r000", "label", "Escrivaninha",
                        "direction", "left", "distanceMm", 300.0,
                        "partsMoved", 6,
                        "bboxBefore", Map.of("x0", 100.0, "x1", 140.0,
                                "y0", 200.0, "y1", 220.0, "z0", 0.0),
                        "bboxAfter", Map.of("x0", 100.0, "x1", 140.0,
                                "y0", 200.0, "y1", 220.0, "z0", 0.0)), 5));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.finalAnswer("feito"));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move a escrivaninha 30 cm para a esquerda",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.UNVERIFIED, out.status());
        assertFalse(out.changedSystem());
        assertFalse(host.toolNamesCalled().contains("run_gates"),
                "gate nao valida uma execucao que nem passou pela verificacao");
    }

    // -- contexto entre comandos -------------------------------------------
    @Test
    void oEstadoAprendeComOresultadoRealEalimentaOcomandoSeguinte() {
        var host = FakeCapabilityHost.standard();
        var state = new AgentState("planta_74");
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.finalAnswer("movida"));
        runtime(host, planner, state, 3).execute("move a escrivaninha 30 cm para a esquerda", new AgentTrace("r1", TraceRecorder.NOOP));

        assertEquals("suite_01.escrivaninha", state.lastReferencedObject().orElseThrow());
        assertEquals("r000", state.activeRoom().orElseThrow());
        assertEquals(1, state.pendingEdits());

        var planner2 = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 100))),
                PlannerDecision.finalAnswer("mais 10 cm"));
        runtime(host, planner2, state, 3).execute("move mais 10 cm", new AgentTrace("r2", TraceRecorder.NOOP));

        var ctx = planner2.contexts().get(0);
        assertEquals("suite_01.escrivaninha", ctx.lastReferencedObject(),
                "sem isto, 'move mais 10 cm' obrigaria o modelo a adivinhar o id");
        assertEquals(2, state.pendingEdits());
    }

    @Test
    void undoDiminuiOcontadorDeAlteracoesDesfaziveis() {
        var host = FakeCapabilityHost.standard();
        var state = new AgentState("p");
        runtime(host, new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("x", "left", 10))),
                PlannerDecision.finalAnswer("ok")), state, 3)
                .execute("move 30 cm para a esquerda", new AgentTrace("r1", TraceRecorder.NOOP));
        assertEquals(1, state.pendingEdits());

        runtime(host, new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(new ToolCall("undo", Map.of()))),
                PlannerDecision.finalAnswer("desfeito")), state, 3)
                .execute("desfaz", new AgentTrace("r2", TraceRecorder.NOOP));

        assertEquals(0, state.pendingEdits());
        assertFalse(state.undoAvailable());
    }

    @Test
    void oEstadoNAOaprendeComToolQueFalhou() {
        var host = FakeCapabilityHost.standard();
        host.replace("move_object", args -> ToolResult.failure("move_object", "SCENE_ERROR",
                "travado", Map.of(), 1));
        var state = new AgentState("p");

        runtime(host, new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.cama", "left", 300))),
                PlannerDecision.finalAnswer("não deu")), state, 3)
                .execute("move a cama 30 cm", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(0, state.pendingEdits());
        assertTrue(state.lastReferencedObject().isEmpty());
    }

    @Test
    void travasVaoParaOcontextoDoModelo() {
        var host = FakeCapabilityHost.standard();
        var state = new AgentState("p");
        state.lock("suite_02.cama");
        var planner = new ScriptedPlanner("q", PlannerDecision.finalAnswer("ok"));

        runtime(host, planner, state, 2).execute("organiza o quarto", new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(planner.lastContext().lockedObjects().contains("suite_02.cama"));
    }

    // -- trace ---------------------------------------------------------------
    @Test
    void oTraceRegistraOencadeamentoInteiroNaoSoOtextoFinal() {
        var host = FakeCapabilityHost.standard();
        var recorder = new Capturing();
        var planner = new ScriptedPlanner("qwen-teste",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.finalAnswer("pronto"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("move 30 cm para a esquerda", new AgentTrace("run_abc", recorder));

        assertTrue(recorder.names().contains("run.started"));
        assertTrue(recorder.names().contains("agent.plan"));
        assertTrue(recorder.names().contains("tool.invoke"));
        assertTrue(recorder.names().contains("gate.run"));
        assertTrue(recorder.names().contains("run.finished"));
        assertTrue(recorder.events.stream().allMatch(e -> e.runId().equals("run_abc")));
    }

    @Test
    void aChamadaDeToolEfilhaDoPlanoQueAgerou() {
        var host = FakeCapabilityHost.standard();
        var recorder = new Capturing();
        var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("x", "left", 10))),
                PlannerDecision.finalAnswer("ok"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("move 30 cm para a esquerda", new AgentTrace("run_x", recorder));

        var plan = recorder.events.stream().filter(e -> e.name().equals("agent.plan")).findFirst().orElseThrow();
        var tool = recorder.events.stream().filter(e -> e.name().equals("tool.invoke")).findFirst().orElseThrow();
        assertEquals(plan.spanId(), tool.parentSpanId(),
                "sem pai, o grafo não sabe qual plano gerou qual chamada");
    }

    @Test
    void oTraceDoRunFinishedCarregaOstatusReal() {
        var host = FakeCapabilityHost.standard();
        host.replace("run_gates", args -> ToolResult.success("run_gates",
                Map.of("roomId", "r000", "overall", "FAIL"), 5));
        var recorder = new Capturing();
        var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("x", "left", 10))),
                PlannerDecision.finalAnswer("ficou lindo"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("move 30 cm para a esquerda", new AgentTrace("run_y", recorder));

        var finished = recorder.events.stream()
                .filter(e -> e.name().equals("run.finished")).findFirst().orElseThrow();
        assertEquals("error", finished.status());
        assertEquals("GATE_FAILED", finished.meta().get("status"));
    }

    @Test
    void oResumoDeUmUndoNaoFicaPorContaDoModelo() {
        // Regressao real (2026-09-19): o qwen respondeu "A escrivaninha foi movida
        // 30 cm para a esquerda" como resumo de um DESFAZER. Dados certos, manchete
        // mentindo — e a manchete e' o que se le primeiro.
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(new ToolCall("undo", Map.of()))),
                PlannerDecision.finalAnswer("A escrivaninha foi movida 30 cm para a esquerda."));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("desfaz", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals("alteração desfeita", out.summary());
    }

    @Test
    void oResumoDeUmMoveCONTINUAsendoDoModelo() {
        // O modelo segue dono da prosa onde ela agrega: um move tem nuance
        // (para onde, por que, o que o gate disse) que uma frase fixa perderia.
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.finalAnswer("Movida 30 cm; circulação continua livre."));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move 30 cm para a esquerda", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals("Movida 30 cm; circulação continua livre.", out.summary());
    }

    // -- pedido vago nao vira alteracao ------------------------------------
    @Test
    void medidaQueOusuarioNAOdisseNaoViraAlteracao() {
        // Caso real (2026-09-19): "altere a cama dos quartos" virou
        // move_object(forward, 100mm). O comando nao dizia direcao nem distancia;
        // o modelo preencheu as duas lacunas e o projeto mudou. Os gates ate
        // aprovaram — mover 10 cm nao quebra nada — e e' por isso que gate verde
        // nao conserta alteracao inventada.
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_02.cama", "forward", 100))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("altere a cama dos quartos", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("move_object"),
                "nada pode ter sido movido");
        assertTrue(out.summary().contains("não vou inventar"));
        assertEquals(1, out.options().size(), "a proposta volta para o Felipe confirmar");
    }

    @Test
    void medidaEmDIGITOnoComandoLibera() {
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.finalAnswer("movida"));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move a escrivaninha 30 cm para a esquerda",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("move_object"));
        assertEquals(AgentOutcome.Status.CLEAN, out.status());
    }

    @Test
    void medidaPorEXTENSOnoComandoTambemLibera() {
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 100))),
                PlannerDecision.finalAnswer("ok"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("empurra a mesa dez centimetros para a esquerda",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("move_object"));
    }

    @Test
    void aGuardaNAOatrapalhaToolQueNaoDependeDeMedida() {
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(new ToolCall("undo", Map.of()))),
                PlannerDecision.finalAnswer("desfeito"));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("desfaz", new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("undo"));
        assertEquals(AgentOutcome.Status.CLEAN, out.status());
    }

    @Test
    void oPlannerRECEBEaListaDoQueNaoExiste() {
        // Caso real: "coloque um lencol preto" virou find_object("o lencol preto")
        // e a resposta foi "nao encontrei esse objeto" — quando a verdade e' que
        // material nao esta implementado. O modelo nao estava errando: ele nao
        // tinha como saber.
        final var host = FakeCapabilityHost.standard();
        final var registry = new ToolRegistry(host.describeTools(), host.unsupported());
        final var planner = new RecordingPlanner();

        new AgentRuntime(host, planner, registry, new AgentState("p"), 2, false);

        assertEquals(List.of("render"),
                planner.informed.stream().map(u -> u.name()).toList());
    }

    /** Planner que só anota o que lhe contaram. */
    private static final class RecordingPlanner implements harness.agent.domain.LlmPlanner {
        final List<harness.agent.domain.UnsupportedCapability> informed = new ArrayList<>();

        @Override
        public void knowsUnsupported(
                final List<harness.agent.domain.UnsupportedCapability> unsupported) {
            this.informed.addAll(unsupported);
        }

        @Override
        public PlannerDecision plan(final AgentContext c,
                                    final List<harness.agent.domain.ToolSpec> t,
                                    final List<Step> h) {
            return PlannerDecision.finalAnswer("ok");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String modelName() {
            return "gravador";
        }
    }
}
