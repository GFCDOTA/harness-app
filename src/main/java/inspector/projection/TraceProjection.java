package inspector.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import inspector.domain.ComponentCatalog;
import inspector.domain.ComponentProfile;
import inspector.domain.ExecutionFacts;
import inspector.domain.ImplementationCatalog;
import inspector.domain.SourceVerification;
import inspector.source.SourceVerifier;
import inspector.domain.Pipeline;
import inspector.domain.PipelineEdge;
import inspector.domain.PipelineStep;
import inspector.domain.Run;
import inspector.domain.TraceEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FRONTEIRA — traduz o domínio no payload que a UI consome. Uma direção só:
 * {@code TraceSource → domain → TraceProjection → JSON → React}.
 *
 * <p>A UI nunca vê um objeto Java; vê JSON. É por isso que a bridge não precisa de
 * {@code JSObject} (deprecated e marcado para remoção).
 */
public final class TraceProjection {

    /** Chaves de meta que ganham espaço na caixa, em ordem de importância. */
    private static final List<String> DETAIL_KEYS = List.of(
            "backendRequested", "backendActual", "fallbackTriggered", "fallbackReason",
            "resultingTaxonomy", "indexKind", "collection", "embedModel", "model",
            "nRetrieved", "nSelected", "candidatesCount", "totalTokens", "promptTokens",
            "completionTokens", "totalChars", "sections", "gate", "verdict", "counts",
            "cycle", "fix", "terminal");

    private static final int DETAIL_MAX = 180;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Confere as afirmações sobre o código antes de elas virarem fato na tela.
     * {@code null} = sem verificador; a UI mostra "não verificado", que é diferente
     * de "não existe".
     */
    private final SourceVerifier verifier;

    public TraceProjection() {
        this(null);
    }

    public TraceProjection(final SourceVerifier verifier) {
        this.verifier = verifier;
    }

    public String toJson(final Run run, final String sourceDescription) {
        try {
            return this.mapper.writeValueAsString(toMap(run, sourceDescription));
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("não consegui serializar a projeção da run", e);
        }
    }

    Map<String, Object> toMap(final Run run, final String sourceDescription) {
        Instant t0 = parseTs(run.first().ts());

        List<Map<String, Object>> boxes = new ArrayList<>(run.eventCount());
        for (final TraceEvent e : run.events()) {
            Map<String, Object> box = new LinkedHashMap<>();
            box.put("seq", e.seq());
            box.put("tPlusMs", offsetMs(t0, e.ts()));
            box.put("name", e.name());
            box.put("category", e.category());
            box.put("component", e.component());
            box.put("status", e.status());
            box.put("durationMs", e.durationMs());
            box.put("spanId", e.spanId());
            box.put("parentSpanId", e.parentSpanId());
            box.put("detail", detail(e));
            box.put("kindLabel", ComponentCatalog.profileFor(e.component()).kindLabel());
            boxes.add(box);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.runId());
        out.put("source", sourceDescription);
        out.put("eventCount", run.eventCount());
        out.put("spanCount", run.spans().size());
        out.put("durationMs", run.durationMs());
        out.put("terminalStatus", run.terminalStatus());
        out.put("boxes", boxes);
        out.put("pipeline", pipelineOf(run));
        return out;
    }

    /** A visao de grafo: passos e ligacoes, para a Pipeline View. */
    Map<String, Object> pipelineOf(final Run run) {
        Pipeline p = Pipeline.from(run);

        List<Map<String, Object>> nodes = new ArrayList<>(p.steps().size());
        for (final PipelineStep st : p.steps()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", st.id());
            n.put("category", st.category());
            n.put("component", st.dominantComponent());
            n.put("componentFamily", st.componentFamily());
            n.put("status", st.status());
            n.put("durationMs", st.durationMs());
            n.put("fallbackEntry", st.fallbackEntry());
            n.put("profile", profileMap(st.dominantComponent()));
            n.put("seqFrom", st.seqFrom());
            n.put("seqTo", st.seqTo());
            n.put("eventCount", st.eventCount());
            n.put("detail", stepDetail(st));

            List<Map<String, Object>> measurements = new ArrayList<>(st.eventCount());
            for (final TraceEvent e : st.events()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seq", e.seq());
                m.put("name", e.name());
                m.put("component", e.component());
                m.put("status", e.status());
                m.put("durationMs", e.durationMs());
                m.put("detail", detail(e));
                m.put("ts", e.ts());
                m.put("spanId", e.spanId());
                m.put("parentSpanId", e.parentSpanId());
                // meta CRU, para o deep-dive: `detail` e curado e esconde chave que
                // ninguem previu. O painel precisa poder mostrar tudo que existe.
                m.put("meta", e.meta());
                measurements.add(m);
            }
            n.put("measurements", measurements);
            nodes.add(n);
        }

        List<Map<String, Object>> edges = new ArrayList<>(p.edges().size());
        for (final PipelineEdge e : p.edges()) {
            Map<String, Object> ed = new LinkedHashMap<>();
            ed.put("id", e.id());
            ed.put("source", e.from());
            ed.put("target", e.to());
            ed.put("kind", e.kind());
            ed.put("fallback", e.isFallback());
            edges.add(ed);
        }

        List<Map<String, Object>> calls = new ArrayList<>(p.calls().size());
        for (final PipelineEdge e : p.calls()) {
            Map<String, Object> ed = new LinkedHashMap<>();
            ed.put("id", e.id());
            ed.put("source", e.from());
            ed.put("target", e.to());
            ed.put("kind", e.kind());
            calls.add(ed);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        // quem chamou quem, derivado de parentSpanId — para a expansao semantica
        out.put("calls", calls);
        return out;
    }

    /** Detalhe do passo: o primeiro evento do grupo que tenha algo a dizer. */
    static String stepDetail(final PipelineStep st) {
        for (final TraceEvent e : st.events()) {
            final var d = detail(e);
            if (!d.isEmpty()) return d;
        }
        return "";
    }

    /**
     * O que o componente É — nome humano, onde roda, o que recebe, o que devolve.
     *
     * <p>Substituiu o booleano {@code external}, que dizia apenas "fora do processo
     * Python" mas aparecia na tela como "HTTP externo" e induzia ao erro: Ollama e
     * Qdrant rodam NESTA máquina.
     */
    Map<String, Object> profileMap(final String component) {
        ComponentProfile p = ComponentCatalog.profileFor(component);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("catalogued", p.catalogued());
        m.put("humanName", p.humanName());
        m.put("kind", p.kind());
        m.put("kindLabel", p.kindLabel());
        m.put("transport", p.transport());
        m.put("host", p.host());
        m.put("endpoint", p.endpoint());
        m.put("role", p.role());
        m.put("input", p.input());
        m.put("output", p.output());
        m.put("why", p.why());
        m.put("code", p.code());
        m.put("concepts", p.concepts());
        m.put("implementation", implementationMap(component));
        return m;
    }

    /**
     * QUEM executa — com PROCEDÊNCIA. Cada campo do bloco {@code impl} é afirmação
     * sobre o código e vem acompanhado do resultado da verificação; {@code pattern} e
     * {@code concepts} são explicação e estão marcados como tal.
     */
    Map<String, Object> implementationMap(final String component) {
        ExecutionFacts f = ImplementationCatalog.factsFor(component);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("declared", !f.impl().isEmpty());
        m.put("language", f.impl().language());
        m.put("module", f.impl().module());
        m.put("symbol", f.impl().symbol());
        m.put("libraries", f.impl().libraries());
        m.put("runtime", f.impl().runtime());
        m.put("decision", f.decision());
        m.put("pattern", f.pattern());
        m.put("llmInvolved", f.llmInvolved());

        SourceVerification v = verifier == null
                ? SourceVerification.notChecked("verificador não configurado")
                : this.verifier.verify(f.impl());
        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("checked", v.checked());
        vm.put("moduleFound", v.moduleFound());
        vm.put("symbolFound", v.symbolFound());
        vm.put("librariesFound", v.librariesFound());
        vm.put("librariesMissing", v.librariesMissing());
        vm.put("fullyVerified", v.fullyVerified());
        vm.put("note", v.note());
        m.put("verification", vm);
        return m;
    }

    static String detail(final TraceEvent e) {
        Map<String, Object> meta = e.meta();
        StringBuilder sb = new StringBuilder();
        for (final String k : DETAIL_KEYS) {
            if (!meta.containsKey(k)) continue;
            final var v = meta.get(k);
            if (v == null) continue;
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append(k).append('=').append(v);
            if (sb.length() >= DETAIL_MAX) break;
        }
        if (sb.length() > DETAIL_MAX) {
            return sb.substring(0, DETAIL_MAX - 1) + "…";
        }
        return sb.toString();
    }

    private static Instant parseTs(final String ts) {
        if (ts == null) return null;
        try {
            return Instant.parse(ts);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /** Offset desde o início da run. Sem base confiável, devolve null — nunca 0. */
    private static Long offsetMs(final Instant t0, final String ts) {
        Instant t = parseTs(ts);
        if (t0 == null || t == null) return null;
        return t.toEpochMilli() - t0.toEpochMilli();
    }
}
