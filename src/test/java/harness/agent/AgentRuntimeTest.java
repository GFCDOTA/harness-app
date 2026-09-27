package harness.agent;

import harness.agent.domain.AgentOutcome;
import harness.agent.domain.AgentRuntime;
import harness.agent.domain.AgentState;
import harness.agent.domain.AgentTrace;
import harness.agent.domain.PlannerDecision;
import harness.agent.domain.Risk;
import harness.agent.domain.ToolCall;
import harness.agent.domain.ToolSpec;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        // `list_rooms`/`list_objects` entraram na sequencia quando o gate de alvo
        // passou a existir: antes de MUDAR algo, o Harness le a cena REAL para
        // saber se o objeto escolhido e o que o Felipe pediu. Sao leituras, uma vez
        // por comando. O pedaco que importa continua igual: move e DEPOIS gate.
        assertEquals(List.of("find_object", "list_rooms", "list_objects",
                "move_object", "run_gates"), host.toolNamesCalled());
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

    // -- slice 2: a cena vira .skp -----------------------------------------
    /**
     * Registra `apply_to_skp` como o Python o publica: verificacao ARTIFACT.
     * `verified` decide; o resto e evidencia.
     */
    private static FakeCapabilityHost hostComApplyToSkp(final boolean verified,
                                                        final long sizeBytes) {
        final var host = FakeCapabilityHost.standard();
        host.register(new ToolSpec("apply_to_skp", "materializa a cena num .skp",
                        Map.of("type", "object", "properties", Map.of()),
                        // undoable=false: o arquivo ja foi escrito, nao se desfaz.
                        // mutates=true: escrever um .skp MUDA o sistema.
                        Map.of(), "ARTIFACT", true, Risk.MEDIUM, false, true,
                        List.of("pipeline", "SketchUp", "scene"), 300),
                args -> ToolResult.success("apply_to_skp",
                        Map.of("verified", verified,
                                "path", "E:/state/materialized/planta_74_harness.skp",
                                "sizeBytes", sizeBytes,
                                "boxes", 42), 9));
        return host;
    }

    @Test
    void artefatoEscritoEverificadoTerminaClean() {
        final var host = hostComApplyToSkp(true, 3_426_916L);
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(new ToolCall("apply_to_skp", Map.of()))),
                PlannerDecision.finalAnswer("materializado"));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("aplica no skp", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.CLEAN, out.status());
        assertTrue(out.changedSystem());
    }

    @Test
    void skpDeZeroByteNuncaViraSucessoNoAgente() {
        // O .skb de 0 byte e a falha classica do SketchUp em lote. O Python ja
        // marca verified=false; aqui travamos que o agente NAO reinterpreta isso
        // como sucesso so porque a chamada nao lancou excecao.
        final var host = hostComApplyToSkp(false, 0L);
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(new ToolCall("apply_to_skp", Map.of()))),
                PlannerDecision.finalAnswer("materializado"));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("aplica no skp", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.UNVERIFIED, out.status());
        assertFalse(out.changedSystem());
    }

    // -- slice 4: cor ------------------------------------------------------
    /**
     * `set_color` como o Python o publica: STATE_DELTA comparando o rgb RELIDO
     * com o PEDIDO. {@code rgbBefore} e so evidencia — nao decide.
     */
    private static FakeCapabilityHost hostComSetColor(final Object rgbBefore,
                                                      final Object rgbAfter,
                                                      final Object rgbRequested) {
        final var host = FakeCapabilityHost.standard();
        host.register(new ToolSpec("set_color", "troca a cor de um objeto",
                        Map.of("type", "object", "properties",
                                Map.of("object_id", Map.of("type", "string"),
                                        // `x-canonical` e publicado pelo Python: nome
                                        // aceito -> canonico. O dubl e' um recorte.
                                        "color", Map.of("type", "string",
                                                "x-canonical", Map.of(
                                                        "preto", "preto", "black", "preto",
                                                        "azul", "azul", "blue", "azul",
                                                        "terracota", "terracota",
                                                        "verde", "verde",
                                                        "verde-escuro", "verde-escuro"))),
                                "required", List.of("object_id", "color")),
                        Map.of(), "STATE_DELTA", true, Risk.MEDIUM, true, true,
                        List.of("scene"), 30),
                args -> ToolResult.success("set_color",
                        Map.of("objectId", String.valueOf(args.get("object_id")),
                                "roomId", "r000", "label", "Cama",
                                "color", String.valueOf(args.get("color")),
                                "rgbBefore", rgbBefore,
                                "rgbAfter", rgbAfter,
                                "rgbRequested", rgbRequested,
                                "partsPainted", 2,
                                "gatesRun", false), 7));
        return host;
    }

    private static ToolCall pintar() {
        return new ToolCall("set_color", Map.of("object_id", "suite_01.cama",
                "color", "preto"));
    }

    @Test
    void repintarDaCorQueJaEstaContinuaSendoSucesso() {
        // Falso negativo pego rodando o app DE VERDADE (2026-09-21): o modelo
        // chamou set_color duas vezes, e a segunda reprovou porque a cama ja
        // estava preta. Idempotente e o resultado certo, nao falha.
        final var host = hostComSetColor(List.of(26, 26, 28), List.of(26, 26, 28),
                List.of(26, 26, 28));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintar())),
                PlannerDecision.finalAnswer("ja estava preta"));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("troca a cor da cama para preto",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.CLEAN, out.status());
    }

    @Test
    void trocarCorDeixaOcomandoCleanSemRodarGateDeGeometria() {
        final var host = hostComSetColor(List.of(200, 200, 200), List.of(26, 26, 28),
                List.of(26, 26, 28));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintar())),
                PlannerDecision.finalAnswer("pintado"));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("troca a cor da cama para preto",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.CLEAN, out.status());
        assertTrue(out.changedSystem());
        assertFalse(host.toolNamesCalled().contains("run_gates"),
                "cor nao move nada: rodar gate de geometria aqui seria teatro");
    }

    @Test
    void handlerQueDizTerPintadoMasNaoMudouOrgbViraUnverified() {
        // O caso que o CODEX_QUEUE pede explicitamente: "verification pega um
        // handler que diz ter mudado e nao mudou". Pediu preto, releu cinza.
        final var host = hostComSetColor(List.of(200, 200, 200), List.of(200, 200, 200),
                List.of(26, 26, 28));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintar())),
                PlannerDecision.finalAnswer("pintado"));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("troca a cor da cama para preto",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.UNVERIFIED, out.status());
        assertFalse(out.changedSystem());
    }

    // -- a guarda contra o laco do modelo ----------------------------------
    @Test
    void toolQueMUDAestadoNaoRodaDuasVezesNoMesmoComando() {
        // BUG REAL, pego rodando o app (2026-09-21): pedi "desfaz a ultima
        // alteracao" e o modelo chamou `undo` QUATRO vezes, cada uma com
        // sucesso. Resultado: 7 edicoes desfeitas quando o Felipe pediu 1.
        // O laco do modelo e uma propriedade dele; a correcao e deterministica.
        final var host = FakeCapabilityHost.standard();
        final var desfazer = new ToolCall("undo", Map.of());
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(desfazer)),
                PlannerDecision.callTools(List.of(desfazer)),
                PlannerDecision.callTools(List.of(desfazer)),
                PlannerDecision.finalAnswer("desfeito"));

        runtime(host, planner, new AgentState("p"), 4)
                .execute("desfaz a ultima alteracao",
                        new AgentTrace("r", TraceRecorder.NOOP));

        final var vezes = host.toolNamesCalled().stream().filter("undo"::equals).count();
        assertEquals(1, vezes, "undo so podia ter chegado ao host UMA vez");
    }

    @Test
    void repeticaoBloqueadaVolta_como_dado_para_o_modelo_nao_derruba_o_comando() {
        final var host = FakeCapabilityHost.standard();
        final var desfazer = new ToolCall("undo", Map.of());
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(desfazer)),
                PlannerDecision.callTools(List.of(desfazer)),
                PlannerDecision.finalAnswer("pronto"));

        final var out = runtime(host, planner, new AgentState("p"), 4)
                .execute("desfaz a ultima alteracao",
                        new AgentTrace("r", TraceRecorder.NOOP));

        // o comando termina normalmente: a 2a chamada virou ERRO TIPADO de volta
        // pro modelo, nao excecao nem EXHAUSTED
        assertEquals(AgentOutcome.Status.CLEAN, out.status());
        assertTrue(out.actions().stream().anyMatch(a -> !a.ok() && "undo".equals(a.tool())),
                "a repeticao tem que aparecer como acao recusada");
    }

    @Test
    void leituraRepetidaContinuaPermitida() {
        // So tool que MUDA estado e bloqueada. Reler nao destroi nada, e travar
        // leitura quebraria fluxo legitimo (listar, filtrar, listar de novo).
        final var host = FakeCapabilityHost.standard();
        final var achar = new ToolCall("find_object", Map.of("query", "escrivaninha"));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(achar)),
                PlannerDecision.callTools(List.of(achar)),
                PlannerDecision.finalAnswer("achei"));

        runtime(host, planner, new AgentState("p"), 4)
                .execute("onde esta a escrivaninha?",
                        new AgentTrace("r", TraceRecorder.NOOP));

        final var vezes = host.toolNamesCalled().stream()
                .filter("find_object"::equals).count();
        assertEquals(2, vezes);
    }

    @Test
    void tentativasEsgotadasComAlteracaoAplicadaNaoViraExhausted() {
        // Pego rodando o app: o modelo desfez a alteracao, foi bloqueado ao
        // repetir, alucinou uma tool inexistente e nunca concluiu. O desfecho
        // saiu EXHAUSTED "sem chegar a um desfecho" — mentira: o undo ACONTECEU.
        // Quem responde pelo desfecho e o que aconteceu, nao a narrativa do modelo.
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.callTools(List.of(new ToolCall("nao_existe", Map.of()))),
                PlannerDecision.callTools(List.of(new ToolCall("nao_existe", Map.of()))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move a escrivaninha 30 cm para a esquerda",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(out.changedSystem(), "a alteracao aconteceu");
        assertNotEquals(AgentOutcome.Status.EXHAUSTED, out.status(),
                "status nao pode ser EXHAUSTED quando houve alteracao verificada");
    }

    // -- a guarda contra o modelo CONTRARIAR o comando ---------------------
    @Test
    void direcaoContrariaAoComandoNaoExecuta() {
        // BUG REAL (2026-09-21): pedi "trinta centimetros para a ESQUERDA" e o
        // modelo chamou move_object(direction=right, distance_mm=100). A guarda
        // `fabricatedMeasurement` deixou passar porque o comando TINHA uma
        // medida — ela pergunta "o usuario disse algo?", nao "o argumento bate
        // com o que ele disse?". Inverter a direcao do Felipe e pior que inventar.
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "right", 100))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("move a escrivaninha trinta centimetros para a esquerda",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("move_object"),
                "nao pode ter movido para o lado errado");
    }

    @Test
    void direcaoQueBateComOcomandoExecutaNormalmente() {
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "left", 300))),
                PlannerDecision.finalAnswer("movido"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("move a escrivaninha trinta centimetros para a esquerda",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("move_object"));
    }

    @Test
    void comandoSemDirecaoNomeadaNaoEbloqueado() {
        // "afasta da parede" nao nomeia lado; a guarda so age sobre CONTRADICAO.
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "right", 300))),
                PlannerDecision.finalAnswer("movido"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("afasta a escrivaninha trinta centimetros da parede",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("move_object"));
    }

    @Test
    void sinonimoDeDirecaoContaComoAmesmaDirecao() {
        // "para tras" e "back" sao a mesma familia de eixo no registry Python.
        final var host = FakeCapabilityHost.standard();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(move("suite_01.escrivaninha", "back", 300))),
                PlannerDecision.finalAnswer("movido"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("empurra a escrivaninha trinta centimetros para tras",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("move_object"));
    }

    // -- a guarda contra ALTERACAO INVENTADA -------------------------------
    private static ToolCall pintarDe(final String id, final String cor) {
        return new ToolCall("set_color", Map.of("object_id", id, "color", cor));
    }

    @Test
    void corQueNinguemPediuNaoViraAlteracao() {
        // BUG REAL (2026-09-21): pedi "pinta o sofa da sala de terracota" e o
        // modelo pintou o sofa de terracota E a escrivaninha da suite 01 de
        // AZUL. Ninguem falou em escrivaninha nem em azul. A guarda de repeticao
        // nao pega: os argumentos sao diferentes. E a hard rule #3 do repo
        // ("alteracao inventada nao passa"), que so existia para MEDIDA.
        final var host = hostComSetColor(List.of(44, 44, 48), List.of(47, 92, 158),
                List.of(47, 92, 158));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        pintarDe("suite_01.escrivaninha", "azul"))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("pinta o sofa da sala de terracota",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("set_color"),
                "cor que ninguem pediu nao pode ter sido aplicada");
    }

    @Test
    void corQueOcomandoNOMEIApassaNormalmente() {
        final var host = hostComSetColor(List.of(44, 44, 48), List.of(186, 104, 78),
                List.of(186, 104, 78));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        pintarDe("sala.sofa", "terracota"))),
                PlannerDecision.finalAnswer("pintado"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("pinta o sofa da sala de terracota",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("set_color"));
    }

    @Test
    void sinonimoEmInglesNoComandoContaComoCorPedida() {
        // o modelo as vezes responde em ingles mesmo com comando em portugues
        final var host = hostComSetColor(List.of(44, 44, 48), List.of(26, 26, 28),
                List.of(26, 26, 28));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintarDe("suite_01.cama", "preto"))),
                PlannerDecision.finalAnswer("pintado"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("paint the bed black", new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("set_color"));
    }

    @Test
    void statusDizAverdadeMesmoQuandoOtextoDoModeloContradiz() {
        // Caso real (qwen3, 2026-09-27): o modelo pintou o sofa E DEPOIS respondeu
        // "especifique o object_id e a cor, por favor forneca ambos os parametros".
        // O TEXTO fica sendo o do modelo — decisao travada por
        // `oResumoDeUmMoveCONTINUAsendoDoModelo`, porque a prosa dele agrega
        // contexto. O que este teste garante e que o STATUS nao se deixa enganar:
        // houve alteracao verificada, logo CLEAN, e a lista de acoes mostra o que
        // foi feito de verdade.
        final var host = hostComSetColor(List.of(44, 44, 48), List.of(44, 72, 54),
                List.of(44, 72, 54));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintarDe("sala.sofa", "verde"))),
                PlannerDecision.finalAnswer(
                        "Para aplicar uma cor e necessario especificar o object_id "
                                + "e a cor. Por favor, forneca ambos os parametros."));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("pinta o sofa da sala de verde",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.CLEAN, out.status());
        assertTrue(out.changedSystem());
        assertTrue(out.actions().stream().anyMatch(a -> a.ok() && a.mutating()
                        && "set_color".equals(a.tool())),
                "a acao real tem que estar na lista, mesmo com o texto contradizendo");
    }

    // -- buracos achados pelo protocolo de teste da review (2026-09-27) ----
    @Test
    void comandoSemNenhumaCorNaoPodePintarNada() {
        // BUG REAL: pedi "desfaz a ultima alteracao" e o modelo desfez UMA (certo)
        // e DEPOIS pintou o sofa de verde-escuro. Minha guarda `fabricatedColor`
        // deixava passar porque o comando nao nomeia cor NENHUMA — eu tinha
        // liberado esse caso pensando em "deixa a sala mais quente". Errado:
        // pintar sem cor pedida e alteracao inventada, e a contagem de edicoes
        // ainda mascara (undo tira uma, set_color poe outra: total igual).
        final var host = hostComSetColor(List.of(44, 44, 48), List.of(44, 72, 54),
                List.of(44, 72, 54));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintarDe("sala.sofa", "verde"))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("desfaz a ultima alteracao",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("set_color"),
                "sem cor no comando, nao pinta");
    }

    /** `unlock_object` nao esta no dubl padrao; registrar para testar A GUARDA, e
     *  nao o UNKNOWN_TOOL (que faria o teste passar pelo motivo errado). */
    private static FakeCapabilityHost hostComUnlock() {
        final var host = FakeCapabilityHost.standard();
        host.register(new ToolSpec("unlock_object", "destrava um objeto",
                        Map.of("type", "object", "properties",
                                Map.of("object_id", Map.of("type", "string")),
                                "required", List.of("object_id")),
                        Map.of(), "NONE", true, Risk.LOW, false, true,
                        List.of("scene"), 30),
                args -> ToolResult.success("unlock_object",
                        Map.of("unlocked", String.valueOf(args.get("object_id"))), 3));
        return host;
    }

    @Test
    void modeloNaoPodeDESTRAVARparaContornarUmaTrava() {
        // BUG REAL, o mais grave depois do alvo errado: travei a cama, pedi para
        // pintar, o `set_color` recusou CERTO ("esta travado") — e o modelo chamou
        // `unlock_object` e destravou. Trava que o modelo desfaz sozinho nao e
        // protecao. Destravar so acontece se o Felipe pedir.
        final var host = hostComUnlock();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        new ToolCall("unlock_object", Map.of("object_id", "suite_01.cama")))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("pinta a cama da suite 01 de preto",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("unlock_object"),
                "destravar nao foi pedido");
    }

    @Test
    void destravarFuncionaQuandoOcomandoPEDE() {
        final var host = hostComUnlock();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        new ToolCall("unlock_object", Map.of("object_id", "suite_01.cama")))),
                PlannerDecision.finalAnswer("destravada"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("destrava a cama da suite 01",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("unlock_object"));
    }

    // -- o gate de alvo no laco (regras 5, 7 e 8 da review) ----------------
    /** Dubl com o indice de cena REAL disponivel por list_rooms/list_objects. */
    private static FakeCapabilityHost hostComCena() {
        final var host = hostComSetColor(List.of(44, 44, 48), List.of(44, 72, 54),
                List.of(44, 72, 54));
        host.register(new ToolSpec("list_rooms", "lista comodos",
                        Map.of("type", "object", "properties", Map.of()),
                        Map.of(), "NONE", true, Risk.LOW, false, false, List.of(), 30),
                args -> ToolResult.success("list_rooms",
                        Map.of("rooms", List.of(
                                Map.of("id", "r000", "name", "SUITE 01"),
                                Map.of("id", "r002", "name", "SALA DE JANTAR | SALA DE ESTAR"),
                                Map.of("id", "r004", "name", "COZINHA"))), 2));
        host.register(new ToolSpec("list_objects", "lista objetos",
                        Map.of("type", "object", "properties", Map.of()),
                        Map.of(), "NONE", true, Risk.LOW, false, false, List.of(), 30),
                args -> ToolResult.success("list_objects",
                        Map.of("objects", List.of(
                                Map.of("id", "suite_01.cama", "roomId", "r000",
                                        "room", "SUITE 01", "label", "Cama",
                                        "kinds", List.of("bed")),
                                Map.of("id", "sala_de_jantar_sala_de_estar.sofa", "roomId", "r002",
                                        "room", "SALA DE JANTAR | SALA DE ESTAR", "label", "Sofa",
                                        "kinds", List.of("sofa")),
                                Map.of("id", "cozinha.upper_cabinet_01", "roomId", "r004",
                                        "room", "COZINHA", "label", "upper_cabinet_01",
                                        "kinds", List.of("upper_cabinet")))), 3));
        return host;
    }

    @Test
    void oBUGdoAlvoErradoNaoPassaMaisPeloLaco() {
        // "pinta o armario da COZINHA" tentando pintar o SOFA DA SALA: era o P0,
        // e o desfecho saia CLEAN.
        final var host = hostComCena();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        pintarDe("sala_de_jantar_sala_de_estar.sofa", "verde-escuro"))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("pinta o armario da cozinha de verde-escuro",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(out.changedSystem(), "nenhuma mutacao podia ter acontecido");
        assertFalse(host.toolNamesCalled().contains("set_color"));
    }

    @Test
    void oBloqueioDeAlvoDEIXArastroAuditavelNoTrace() {
        final var host = hostComCena();
        final var recorder = new Capturing();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        pintarDe("sala_de_jantar_sala_de_estar.sofa", "verde-escuro"))));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("pinta o armario da cozinha de verde-escuro",
                        new AgentTrace("r", recorder));

        assertTrue(recorder.names().contains("target.guard.blocked"),
                "sem evento de procedencia nao da pra auditar: " + recorder.names());
        final var evento = recorder.events.stream()
                .filter(e -> "target.guard.blocked".equals(e.name())).findFirst().orElseThrow();
        assertEquals("ROOM_MISMATCH", evento.meta().get("status"));
        assertTrue(evento.meta().containsKey("candidates"), "os candidatos reais tem que estar la");
    }

    @Test
    void alvoCorretoDentroDoComodoNomeadoPASSA() {
        final var host = hostComCena();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        pintarDe("cozinha.upper_cabinet_01", "verde-escuro"))),
                PlannerDecision.finalAnswer("pintado"));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("pinta o objeto cozinha.upper_cabinet_01 de verde-escuro",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.CLEAN, out.status());
        assertTrue(host.toolNamesCalled().contains("set_color"));
    }

    @Test
    void idInexistenteComSUGESTAOvoltaComoDadoEoModeloSeCorrige() {
        // Pego ao vivo: o modelo mandou "a cama da suite 01" como se fosse id. A
        // sugestao existia mas o bloqueio encerrava o turno, entao ele nao podia
        // usa-la. Mesma licao do UNKNOWN_TOOL: erro que volta como DADO faz ele se
        // corrigir; erro que encerra, nao. Seguro porque a sugestao vem do resolver
        // DETERMINISTICO, e o id corrigido passa pelo gate igual.
        final var host = hostComCena();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintarDe("a cama da suite 01", "preto"))),
                PlannerDecision.callTools(List.of(pintarDe("suite_01.cama", "preto"))),
                PlannerDecision.finalAnswer("pintado"));

        final var out = runtime(host, planner, new AgentState("p"), 4)
                .execute("pinta a cama da suite 01 de preto",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.CLEAN, out.status());
        assertTrue(host.toolNamesCalled().contains("set_color"),
                "depois da dica, a chamada certa tinha que rodar");
    }

    @Test
    void mismatchDeComodoCONTINUAencerrandoOturno() {
        // NOT_FOUND com sugestao e recuperavel. ROOM_MISMATCH nao: ali o modelo
        // escolheu objeto de outro comodo, e quem decide e o Felipe — nao se
        // "tenta de novo" um alvo que contraria o pedido.
        final var host = hostComCena();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        pintarDe("sala_de_jantar_sala_de_estar.sofa", "verde-escuro"))),
                PlannerDecision.finalAnswer("nao deveria chegar aqui"));

        final var out = runtime(host, planner, new AgentState("p"), 4)
                .execute("pinta o armario da cozinha de verde-escuro",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
    }

    /** `lock_object` alem do `unlock_object` — para testar o par oposto. */
    private static FakeCapabilityHost hostComLockEunlock() {
        final var host = hostComUnlock();
        host.register(new ToolSpec("lock_object", "trava um objeto",
                        Map.of("type", "object", "properties",
                                Map.of("object_id", Map.of("type", "string")),
                                "required", List.of("object_id")),
                        Map.of(), "NONE", true, Risk.LOW, false, true,
                        List.of("scene"), 30),
                args -> ToolResult.success("lock_object",
                        Map.of("locked", String.valueOf(args.get("object_id"))), 3));
        return host;
    }

    @Test
    void pedirDESTRAVARnaoPodeAcabarTRAVANDO() {
        // BUG REAL pego rodando ao vivo: comando "destrava a cama da suite 01".
        // O unlock falhou por id errado, o modelo recebeu a dica e "corrigiu"
        // chamando `lock_object`. TRAVOU o que se pediu para destravar, e o resumo
        // disse "travada com sucesso" — verdade, e o oposto do pedido.
        final var host = hostComLockEunlock();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        new ToolCall("lock_object", Map.of("object_id", "suite_01.cama")))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("destrava a cama da suite 01",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("lock_object"),
                "nao fazer o OPOSTO do que foi pedido");
    }

    @Test
    void travarQuandoOcomandoPEDEtravarFunciona() {
        // "destrava" CONTEM "trava": a guarda nao pode confundir os dois.
        final var host = hostComLockEunlock();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(
                        new ToolCall("lock_object", Map.of("object_id", "suite_01.cama")))),
                PlannerDecision.finalAnswer("travada"));

        runtime(host, planner, new AgentState("p"), 3)
                .execute("trava a cama da suite 01", new AgentTrace("r", TraceRecorder.NOOP));

        assertTrue(host.toolNamesCalled().contains("lock_object"));
    }

    @Test
    void operacaoDeControleQueNinguemPediuNaoRoda() {
        // O comando fala de cor; `undo` nao foi pedido em lugar nenhum.
        final var host = hostComCena();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(new ToolCall("undo", Map.of()))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("pinta a cama da suite 01 de preto",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("undo"));
    }

    @Test
    void recusaNAOapagaOqueJAfoiFeitoAntesDela() {
        // BUG REAL pego ao vivo: "pinta o objeto X de dourado" PINTOU o sofa e
        // depois o modelo encadeou `apply_to_skp` sem ter sido pedido. A guarda
        // barrou — certo — mas o desfecho mostrava SO a recusa. O Felipe lia
        // "nao pede apply_to_skp" com o sofa ja dourado, sem saber disso.
        // Metade da verdade num relatorio e pior que verbosidade.
        final var host = hostComCena();
        host.register(new ToolSpec("apply_to_skp", "materializa",
                        Map.of("type", "object", "properties", Map.of()),
                        Map.of(), "ARTIFACT", true, Risk.MEDIUM, false, true,
                        List.of("pipeline"), 300),
                args -> ToolResult.success("apply_to_skp", Map.of("verified", true), 9));
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintarDe("suite_01.cama", "preto"))),
                PlannerDecision.callTools(List.of(new ToolCall("apply_to_skp", Map.of()))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("pinta a cama da suite 01 de preto",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertTrue(out.summary().contains("Parei aqui"),
                "a recusa tem que vir DEPOIS do que foi feito: " + out.summary());
        assertTrue(out.changedSystem(), "a pintura aconteceu e o desfecho tem que dizer");
        // e o "que foi feito" tem que DESCREVER, nao dizer "ok". Sem isto o fix
        // e cosmetico: "ok. Parei aqui: ..." nao informa que o objeto foi pintado.
        assertTrue(out.summary().contains("pintada de"),
                "o relato tem que dizer O QUE foi feito: " + out.summary());
    }

    // -- regra 7: exclusividade de intencao ---------------------------------
    @Test
    void turnoDeCONTROLEnaoRodaFerramentaQueAltera() {
        // "desfaz a ultima alteracao" executou undo E DEPOIS pintou o sofa. Guarda
        // por combinacao nao escala; o turno inteiro e que nao autoriza a familia.
        final var host = hostComCena();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintarDe("suite_01.cama", "preto"))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("desfaz a ultima alteracao", new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("set_color"));
    }

    @Test
    void comandoCOMPOSTOeRECUSADOenquantoNaoHouverTransacao() {
        // Eu tinha LIBERADO este caso ("o Felipe pediu as duas coisas"). Revisto
        // depois de uma review: o risco e ESTADO PARCIAL. Se o undo passa e o
        // set_color e bloqueado pelo gate de alvo, metade do comando mudou a cena
        // e nao ha rollback em bloco. Sem preflight/transacao, recusar e a resposta.
        final var host = hostComCena();
        final var planner = new ScriptedPlanner("q",
                PlannerDecision.callTools(List.of(pintarDe("suite_01.cama", "preto"))));

        final var out = runtime(host, planner, new AgentState("p"), 3)
                .execute("desfaz a ultima alteracao e pinta a cama da suite 01 de preto",
                        new AgentTrace("r", TraceRecorder.NOOP));

        assertEquals(AgentOutcome.Status.NEEDS_FELIPE, out.status());
        assertFalse(host.toolNamesCalled().contains("set_color"),
                "nao executar metade de um comando composto");
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
