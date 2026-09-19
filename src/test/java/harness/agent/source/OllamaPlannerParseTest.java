package harness.agent.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import harness.agent.domain.PlannerDecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tradução da resposta do Ollama — o ponto onde um modelo local erra de verdade.
 *
 * <p>Estes testes não medem inteligência do modelo (isso seria flaky): medem que
 * resposta MALFORMADA nunca vira ação. É a fronteira entre "o texto do LLM" e "o
 * sistema executa".
 */
class OllamaPlannerParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OllamaPlanner planner =
            new OllamaPlanner("http://127.0.0.1:11434", "qwen-teste", Duration.ofSeconds(5));

    private PlannerDecision parse(final String json) throws Exception {
        return planner.parse(MAPPER.readTree(json));
    }

    @Test
    void toolCallComArgumentosEmObjetoViraChamada() throws Exception {
        var d = parse("""
                {"message":{"role":"assistant","content":"","tool_calls":[
                  {"function":{"name":"move_object","arguments":
                    {"object_id":"suite_01.escrivaninha","direction":"left","distance_mm":300}}}]}}
                """);
        assertEquals(PlannerDecision.Kind.CALL_TOOLS, d.kind());
        assertEquals("move_object", d.calls().get(0).tool());
        assertEquals(300, ((Number) d.calls().get(0).args().get("distance_mm")).intValue());
    }

    @Test
    void argumentosComoStringJSONtambemSaoAceitos() throws Exception {
        // alguns modelos locais serializam `arguments` como texto, não objeto
        var d = parse("""
                {"message":{"tool_calls":[{"function":{"name":"find_object",
                  "arguments":"{\\"query\\":\\"mesa\\"}"}}]}}
                """);
        assertEquals("mesa", d.calls().get(0).args().get("query"));
    }

    @Test
    void respostaDeTextoPuroEdesfechoFinal() throws Exception {
        var d = parse("""
                {"message":{"role":"assistant","content":"Movi a escrivaninha 30 cm."}}
                """);
        assertEquals(PlannerDecision.Kind.FINAL, d.kind());
        assertTrue(d.message().contains("Movi"));
    }

    @Test
    void respostaSemMessageNaoViraAcao() throws Exception {
        var d = parse("{\"erro\":\"modelo nao carregado\"}");
        assertEquals(PlannerDecision.Kind.UNAVAILABLE, d.kind());
    }

    @Test
    void respostaVaziaNaoViraAcao() throws Exception {
        var d = parse("{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}");
        assertEquals(PlannerDecision.Kind.UNAVAILABLE, d.kind());
    }

    @Test
    void toolCallSemNomeEdescartadoEmVezDeViraChamadaVazia() throws Exception {
        var d = parse("""
                {"message":{"content":"","tool_calls":[{"function":{"name":"","arguments":{}}}]}}
                """);
        assertEquals(PlannerDecision.Kind.UNAVAILABLE, d.kind());
    }

    @Test
    void argumentosIlegiveisNaoQuebramAchamadaMasChegamVazios() throws Exception {
        // vazio é melhor que inventado: o registry recusa e o modelo vê o erro
        var d = parse("""
                {"message":{"tool_calls":[{"function":{"name":"move_object",
                  "arguments":"{isto nao e json}"}}]}}
                """);
        assertEquals(PlannerDecision.Kind.CALL_TOOLS, d.kind());
        assertTrue(d.calls().get(0).args().isEmpty());
    }

    @Test
    void variasChamadasNoMesmoTurnoSaoPreservadasNaOrdem() throws Exception {
        var d = parse("""
                {"message":{"tool_calls":[
                  {"function":{"name":"find_object","arguments":{"query":"mesa"}}},
                  {"function":{"name":"run_gates","arguments":{"room_id":"r002"}}}]}}
                """);
        assertEquals(java.util.List.of("find_object", "run_gates"),
                d.calls().stream().map(c -> c.tool()).toList());
    }

    @Test
    void ollamaForaDoArNaoEdisponivel() {
        var offline = new OllamaPlanner("http://127.0.0.1:59999", "x", Duration.ofSeconds(1));
        assertTrue(!offline.isAvailable());
    }

    // -- o modelo local que NAO usa o canal de tool calling ------------------

    /**
     * Monta a resposta do Ollama com o `content` DADO, sem escape aninhado.
     *
     * <p>Escrever o JSON dentro de um text block com barra invertida em duas camadas
     * ja produziu um teste que falhava por causa do proprio literal, nao do codigo.
     * Construir a arvore diz exatamente o que o modelo devolveu.
     */
    private PlannerDecision parseContent(final String content) {
        final var root = MAPPER.createObjectNode();
        root.putObject("message").put("role", "assistant").put("content", content);
        return this.planner.parse(root);
    }

    @Test
    void chamadaDeToolESCRITAcomoTextoAindaEumaChamada() {
        // Regressao real (2026-09-19): o qwen2.5-coder:14b despeja a chamada dentro
        // de `content` em vez de usar `tool_calls`. Sem isto o comando terminava
        // ANSWERED, com um blob de JSON como resumo e ZERO tool executada.
        final var decision = parseContent(
                "{\"name\": \"find_object\", \"arguments\": {\"query\": \"a escrivaninha\"}}");

        assertEquals(PlannerDecision.Kind.CALL_TOOLS, decision.kind());
        assertEquals("find_object", decision.calls().get(0).tool());
        assertEquals("a escrivaninha", decision.calls().get(0).args().get("query"));
    }

    @Test
    void chamadaDeToolDentroDeCercaMarkdownTambemVale() {
        final var decision = parseContent(
                "```json\n{\"name\":\"undo\",\"arguments\":{}}\n```");

        assertEquals(PlannerDecision.Kind.CALL_TOOLS, decision.kind());
        assertEquals("undo", decision.calls().get(0).tool());
    }

    @Test
    void formatoComFunctionAninhadaTambemEreconhecido() {
        final var decision = parseContent(
                "{\"function\":{\"name\":\"run_gates\",\"arguments\":{\"room_id\":\"r000\"}}}");

        assertEquals(PlannerDecision.Kind.CALL_TOOLS, decision.kind());
        assertEquals("run_gates", decision.calls().get(0).tool());
    }

    @Test
    void textoEmProsaContinuaSendoRespostaFinalNaoAcao() {
        final var decision = parseContent(
                "Movi a escrivaninha 30 cm para a esquerda e os gates passaram.");

        assertEquals(PlannerDecision.Kind.FINAL, decision.kind());
    }

    @Test
    void jsonSemNomeDeToolNaoViraAcao() {
        final var decision = parseContent("{\"resultado\": \"ok\", \"total\": 3}");

        assertEquals(PlannerDecision.Kind.FINAL, decision.kind(),
                "JSON qualquer nao e chamada de tool");
    }
}
