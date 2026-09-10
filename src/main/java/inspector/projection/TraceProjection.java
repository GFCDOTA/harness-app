package inspector.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public String toJson(Run run, String sourceDescription) {
        try {
            return mapper.writeValueAsString(toMap(run, sourceDescription));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("não consegui serializar a projeção da run", e);
        }
    }

    Map<String, Object> toMap(Run run, String sourceDescription) {
        Instant t0 = parseTs(run.first().ts());

        List<Map<String, Object>> boxes = new ArrayList<>(run.eventCount());
        for (TraceEvent e : run.events()) {
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
            box.put("external", isExternalCall(e));
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
    Map<String, Object> pipelineOf(Run run) {
        Pipeline p = Pipeline.from(run);

        List<Map<String, Object>> nodes = new ArrayList<>(p.steps().size());
        for (PipelineStep st : p.steps()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", st.id());
            n.put("category", st.category());
            n.put("component", st.dominantComponent());
            n.put("componentFamily", st.componentFamily());
            n.put("status", st.status());
            n.put("durationMs", st.durationMs());
            n.put("external", st.external());
            n.put("fallbackEntry", st.fallbackEntry());
            n.put("seqFrom", st.seqFrom());
            n.put("seqTo", st.seqTo());
            n.put("eventCount", st.eventCount());
            n.put("detail", stepDetail(st));

            List<Map<String, Object>> measurements = new ArrayList<>(st.eventCount());
            for (TraceEvent e : st.events()) {
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
        for (PipelineEdge e : p.edges()) {
            Map<String, Object> ed = new LinkedHashMap<>();
            ed.put("id", e.id());
            ed.put("source", e.from());
            ed.put("target", e.to());
            ed.put("fallback", e.fallback());
            edges.add(ed);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /** Detalhe do passo: o primeiro evento do grupo que tenha algo a dizer. */
    static String stepDetail(PipelineStep st) {
        for (TraceEvent e : st.events()) {
            String d = detail(e);
            if (!d.isEmpty()) return d;
        }
        return "";
    }

    /**
     * "Está chamando uma API ou não" — derivado do prefixo do component, que é a
     * convenção que o lado Python já usa ({@code ollama.*}, {@code qdrant.*} são
     * serviço externo por HTTP; o resto é código local).
     */
    static boolean isExternalCall(TraceEvent e) {
        String c = e.component();
        if (c == null) return false;
        return c.startsWith("ollama.") || c.startsWith("qdrant.") || c.startsWith("http.");
    }

    static String detail(TraceEvent e) {
        Map<String, Object> meta = e.meta();
        StringBuilder sb = new StringBuilder();
        for (String k : DETAIL_KEYS) {
            if (!meta.containsKey(k)) continue;
            Object v = meta.get(k);
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

    private static Instant parseTs(String ts) {
        if (ts == null) return null;
        try {
            return Instant.parse(ts);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Offset desde o início da run. Sem base confiável, devolve null — nunca 0. */
    private static Long offsetMs(Instant t0, String ts) {
        Instant t = parseTs(ts);
        if (t0 == null || t == null) return null;
        return t.toEpochMilli() - t0.toEpochMilli();
    }
}
