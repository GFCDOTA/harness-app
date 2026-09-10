package inspector.domain;

import inspector.source.JsonlReplayTraceSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PipelineTest {

    /**
     * Evento SEM span. E o caso que exercita a regra de corrida consecutiva; span
     * proprio por evento faria cada um virar um passo e nao testaria nada disso.
     */
    private static TraceEvent ev(long seq, String comp, String cat, String status,
                                 String name, Map<String, Object> meta) {
        return new TraceEvent(seq, "r1", null, null, "2026-01-01T00:00:00.000Z",
                null, comp, cat, status, name, meta);
    }

    /** Evento COM span e pai — para a regra que manda quando ha span. */
    private static TraceEvent span(long seq, String spanId, String parent, String comp,
                                   String cat, String status, String name) {
        return new TraceEvent(seq, "r1", spanId, parent, "2026-01-01T00:00:00.000Z",
                null, comp, cat, status, name, Map.of());
    }

    private static Run load(Path f) {
        List<TraceEvent> evs = new ArrayList<>();
        new JsonlReplayTraceSource(f).stream(evs::add);
        return Run.fromEvents(evs);
    }

    private static Run fixture() {
        return load(Paths.get("src", "test", "resources", "traces", "sample_run.jsonl"));
    }

    @Test
    void terminaisDaRunNaoSaoPassos() {
        Pipeline p = Pipeline.from(fixture());
        for (PipelineStep st : p.steps()) {
            for (TraceEvent e : st.events()) {
                assertNotEquals("run.started", e.name());
                assertNotEquals("run.finished", e.name());
            }
        }
    }

    @Test
    void familiaDoComponentEhOTrechoAntesDoPrimeiroPonto() {
        assertEquals("gate", Pipeline.family("gate.opening_host"));
        assertEquals("ollama", Pipeline.family("ollama.deepseek-r1:14b"));
        assertEquals("correction_loop", Pipeline.family("correction_loop"));
        assertEquals("?", Pipeline.family(null));
    }

    @Test
    void osTresGatesColapsamNumPassoUnicoPelaFamilia() {
        Run run = Run.fromEvents(List.of(
                ev(1, "gate.run_deterministic_gates", "DETERMINISTIC", "ok", "gate.passed", Map.of()),
                ev(2, "gate.opening_host", "DETERMINISTIC", "ok", "gate.passed", Map.of()),
                ev(3, "gate.wall_overlap", "DETERMINISTIC", "ok", "gate.passed", Map.of())));
        Pipeline p = Pipeline.from(run);
        assertEquals(1, p.steps().size(), "3 gates de components distintos devem virar UMA caixa");
        assertEquals(3, p.steps().getFirst().eventCount());
        assertEquals("gate", p.steps().getFirst().componentFamily());
    }

    @Test
    void harnessKindColapsaOLoopInteiroMesmoComComponentsDiferentes() {
        Map<String, Object> hk = Map.of("harnessKind", "APPLICATION_HARNESS");
        Run run = Run.fromEvents(List.of(
                ev(1, "correction_loop", "HARNESS", "started", "harness.cycle.started", hk),
                ev(2, "finding_router", "HARNESS", "ok", "harness.classify", hk),
                ev(3, "correction_fixes", "HARNESS", "ok", "agent.correction", hk),
                ev(4, "correction_loop", "HARNESS", "ok", "harness.terminal", hk)));
        Pipeline p = Pipeline.from(run);
        assertEquals(1, p.steps().size(),
                "harnessKind e o sinal do lado Python que funde o loop num passo");
        assertEquals(4, p.steps().getFirst().eventCount());
    }

    @Test
    void semHarnessKindComponentsDiferentesNaoColapsam() {
        Run run = Run.fromEvents(List.of(
                ev(1, "correction_loop", "HARNESS", "ok", "harness.detect", Map.of()),
                ev(2, "finding_router", "HARNESS", "ok", "harness.classify", Map.of())));
        assertEquals(2, Pipeline.from(run).steps().size(),
                "sem o sinal, a fusao seria invencao minha");
    }

    @Test
    void oPiorStatusDoGrupoManda() {
        Pipeline p = Pipeline.from(fixture());
        PipelineStep qdrant = p.steps().stream()
                .filter(st -> "qdrant".equals(st.componentFamily())).findFirst().orElseThrow();
        assertEquals("failed", qdrant.status());
    }

    @Test
    void aArestaQueEntraNoPassoDeFallbackEhMarcadaComoFallback() {
        Pipeline p = Pipeline.from(fixture());
        PipelineStep fb = p.steps().stream().filter(PipelineStep::fallbackEntry).findFirst().orElseThrow();
        List<PipelineEdge> entrando = p.edges().stream().filter(e -> e.to().equals(fb.id())).toList();
        assertEquals(1, entrando.size());
        assertTrue(entrando.getFirst().isFallback(),
                "o desvio precisa ser visivel como desvio, nao como passo normal");
    }

    @Test
    void semNenhumaMedidaADuracaoDoPassoEhNulaNuncaZero() {
        TraceEvent e = new TraceEvent(1L, "r1", "s1", null, "2026-01-01T00:00:00.000Z",
                null, "comp.x", "RAG", "ok", "rag.query.started", java.util.Map.of());
        assertNull(Pipeline.maxDuration(List.of(e)));
    }

    @Test
    void voltarAUmaChaveAnteriorAbrePassoNovo() {
        Run run = Run.fromEvents(List.of(
                ev(1, "reference_db.retrieve", "RAG", "started", "rag.query.started", Map.of()),
                ev(2, "qdrant.rag_chunks", "RAG", "failed", "rag.retrieval.finished", Map.of()),
                ev(3, "reference_db.faceted", "RAG", "ok", "rag.retrieval.finished", Map.of())));
        List<String> familias = Pipeline.from(run).steps().stream()
                .map(PipelineStep::componentFamily).toList();
        assertEquals(List.of("reference_db", "qdrant", "reference_db"), familias,
                "o pipeline passou por reference_db duas vezes; sao duas caixas, nao uma");
    }

    /** OPT-IN: o trace real e gitignored na origem. Documenta o alvo de 8 passos. */
    @Test
    void traceRealDeBanheiroDaOitoPassosQuandoPresente() {
        Path real = Paths.get("traces-local", "run_20260827T021348Z_banho.jsonl");
        assumeTrue(Files.exists(real), "trace real ausente: teste pulado");
        Pipeline p = Pipeline.from(load(real));
        System.out.println("--- pipeline derivado do trace real ---");
        for (PipelineStep st : p.steps()) {
            ComponentProfile perfil = ComponentCatalog.profileFor(st.dominantComponent());
            System.out.printf("%-4s %-24s %-28s %-9s %10s %-14s fb=%-5s seq %d-%d (%d ev)%n",
                    st.id(), perfil.humanName(), st.dominantComponent(), st.status(),
                    st.durationMs() == null ? "-" : Math.round(st.durationMs()) + "ms",
                    perfil.kindLabel(), st.fallbackEntry(), st.seqFrom(), st.seqTo(), st.eventCount());
        }
        assertEquals(8, p.steps().size(), "o alvo pedagogico e 7-8 caixas");
        assertEquals(7, p.edges().size());
        assertEquals(1, p.edges().stream().filter(PipelineEdge::isFallback).count());
    }

    @Test
    void startedNaoVenceDeUmDesfecho() {
        Run run = Run.fromEvents(List.of(
                ev(1, "ollama.x", "RAG", "started", "rag.embedding.started", Map.of()),
                ev(2, "ollama.x", "RAG", "ok", "rag.embedding.finished", Map.of())));
        assertEquals("ok", Pipeline.from(run).steps().getFirst().status(),
                "um passo que abriu E terminou nao pode aparecer como 'started' para sempre");
    }

    @Test
    void passoQueSoAbriuFicaRunning() {
        Run run = Run.fromEvents(List.of(
                ev(1, "ollama.x", "RAG", "started", "rag.embedding.started", Map.of())));
        assertEquals(Pipeline.RUNNING, Pipeline.from(run).steps().getFirst().status(),
                "e o estado que a UI precisa acender quando o SSE chegar");
    }

    @Test
    void passoSemStatusAlgumFicaNulo() {
        Run run = Run.fromEvents(List.of(
                ev(1, "comp.x", "RAG", null, "rag.something", Map.of())));
        assertNull(Pipeline.from(run).steps().getFirst().status());
    }

    @Test
    void oSpanManda_eventoDoMesmoSpanVoltaParaOmesmoPassoAindaQueNaoSejaContiguo() {
        Run run = Run.fromEvents(List.of(
                span(1, "s1", null, "reference_db.retrieve", "RAG", "started", "rag.query.started"),
                span(2, "s2", "s1", "ollama.x", "RAG", "ok", "rag.embedding.finished"),
                span(3, "s1", null, "reference_db.retrieve", "RAG", "degraded", "rag.query.finished")));
        Pipeline p = Pipeline.from(run);
        assertEquals(2, p.steps().size(), "seq 1 e 3 sao o MESMO span: um passo so");
        assertEquals(2, p.steps().getFirst().eventCount(),
                "o span s1 tem abertura e fechamento; o do meio pertence ao filho");
        assertEquals("degraded", p.steps().getFirst().status(),
                "o evento de fechamento voltou para o passo certo, entao ele nao fica running");
    }

    @Test
    void quemChamouQuemSaiDeParentSpanId_naoDeSuposicao() {
        Run run = Run.fromEvents(List.of(
                span(1, "s1", null, "reference_db.retrieve", "RAG", "started", "rag.query.started"),
                span(2, "s2", "s1", "ollama.x", "RAG", "ok", "rag.embedding.finished"),
                span(3, "s3", "s1", "qdrant.y", "RAG", "failed", "rag.retrieval.finished"),
                span(4, "s1", null, "reference_db.retrieve", "RAG", "degraded", "rag.query.finished")));
        Pipeline p = Pipeline.from(run);
        String orquestrador = p.steps().getFirst().id();
        List<String> chamados = p.calls().stream()
                .filter(e -> e.from().equals(orquestrador)).map(PipelineEdge::to).toList();
        assertEquals(2, chamados.size(), "o retrieve chamou ollama e qdrant: " + p.calls());
        assertTrue(p.calls().stream().allMatch(e -> PipelineEdge.CALL.equals(e.kind())));
    }

    @Test
    void oPassoDoFallbackNaoVira_paiDasChamadasDoOrquestrador() {
        Run run = Run.fromEvents(List.of(
                span(1, "s1", null, "reference_db.retrieve", "RAG", "started", "rag.query.started"),
                span(2, "s2", "s1", "ollama.x", "RAG", "ok", "rag.embedding.finished"),
                span(3, "s4", "s1", "reference_db.faceted", "RAG", "ok", "rag.retrieval.finished"),
                span(4, "s1", null, "reference_db.retrieve", "RAG", "degraded", "rag.query.finished")));
        Pipeline p = Pipeline.from(run);
        String fallback = p.steps().stream()
                .filter(st -> "reference_db.faceted".equals(st.dominantComponent()))
                .findFirst().orElseThrow().id();
        assertTrue(p.calls().stream().noneMatch(e -> e.from().equals(fallback)),
                "o fallback nao chamou ninguem; era artefato do agrupamento antigo");
    }

    @Test
    void semSpanAlgumNaoHaArestaDeChamada() {
        Run run = Run.fromEvents(List.of(
                ev(1, "a.x", "RAG", "ok", "n1", Map.of()),
                ev(2, "b.y", "LLM", "ok", "n2", Map.of())));
        assertTrue(Pipeline.from(run).calls().isEmpty(), "sem parentSpanId nao se inventa chamada");
    }
}
