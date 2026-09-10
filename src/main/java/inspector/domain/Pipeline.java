package inspector.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * A visão de PIPELINE de uma run: passos e ligações, derivados dos eventos.
 *
 * <p><b>Regra de agrupamento</b> (nesta ordem, e nada além disso):
 * <ol>
 *   <li>se o evento traz {@code meta.harnessKind}, a chave do passo é esse valor —
 *       é o proprio lado Python declarando "isto e uma camada de harness", e e o que
 *       colapsa os 10 eventos do correction loop num passo unico;</li>
 *   <li>senao, a chave e {@code category + familia do component}, onde familia e o
 *       trecho antes do primeiro ponto ({@code gate.opening_host} -> {@code gate}).
 *       E o que funde os 3 gates numa caixa e separa embedding de vector-db.</li>
 * </ol>
 * Corridas CONSECUTIVAS de mesma chave viram um passo. Voltar a uma chave anterior
 * depois de outra abre passo novo — porque o pipeline realmente passou por ali de novo.
 *
 * <p>Os eventos {@code run.started} / {@code run.finished} nao sao passos: sao os
 * TERMINAIS da run, e a UI os desenha como inicio e fim.
 */
public record Pipeline(List<PipelineStep> steps, List<PipelineEdge> edges, List<PipelineEdge> calls) {

    public Pipeline {
        steps = List.copyOf(steps);
        edges = List.copyOf(edges);
        calls = List.copyOf(calls);
    }

    /**
     * Precedencia de DESFECHO: o pior do grupo manda. {@code started} de proposito NAO
     * esta aqui — e marcador de ciclo de vida, nao desfecho. Se competisse, um passo
     * que abriu e terminou em 2 s apareceria como "started" para sempre.
     */
    private static final List<String> OUTCOMES = List.of("failed", "degraded", "skipped", "ok");
    static final String RUNNING = "running";

    public static Pipeline from(Run run) {
        List<List<TraceEvent>> groups = group(run.events());

        List<PipelineStep> steps = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            steps.add(toStep("s" + (i + 1), groups.get(i)));
        }

        List<PipelineEdge> edges = new ArrayList<>();
        for (int i = 1; i < steps.size(); i++) {
            PipelineStep prev = steps.get(i - 1);
            PipelineStep cur = steps.get(i);
            edges.add(cur.fallbackEntry()
                    ? PipelineEdge.fallback(prev.id(), cur.id())
                    : PipelineEdge.sequence(prev.id(), cur.id()));
        }
        return new Pipeline(steps, edges, callEdges(steps));
    }

    /**
     * Agrupa em passos. O {@code spanId} manda quando existe.
     *
     * <p>Antes o agrupamento era so por corrida CONSECUTIVA de chave, e isso quebrava
     * em dois lugares de uma vez: o evento que FECHA o span do orquestrador caia no
     * passo seguinte (o do fallback), o que deixava o orquestrador eternamente
     * {@code running} e ainda fazia o fallback parecer o pai das chamadas ao Ollama e
     * ao Qdrant. Span e a unidade real de trabalho — respeitar isso conserta os dois.
     *
     * <p>Evento SEM span (gates, correction loop, marcadores) continua na regra de
     * corrida consecutiva: {@code harnessKind}, senao categoria + familia.
     */
    private static List<List<TraceEvent>> group(List<TraceEvent> events) {
        List<List<TraceEvent>> groups = new ArrayList<>();
        Map<String, List<TraceEvent>> bySpan = new LinkedHashMap<>();
        String currentKey = null;
        List<TraceEvent> spanless = null;

        for (TraceEvent e : events) {
            if (isTerminalMarker(e)) continue;

            String span = e.spanId();
            if (span != null && !span.isBlank()) {
                List<TraceEvent> grupo = bySpan.get(span);
                if (grupo == null) {
                    grupo = new ArrayList<>();
                    bySpan.put(span, grupo);
                    groups.add(grupo);
                }
                grupo.add(e);
                // um span interrompe a corrida dos sem-span
                currentKey = null;
                spanless = null;
                continue;
            }

            String key = stepKey(e);
            if (spanless == null || !key.equals(currentKey)) {
                spanless = new ArrayList<>();
                groups.add(spanless);
                currentKey = key;
            }
            spanless.add(e);
        }
        return groups;
    }

    /**
     * Quem CHAMOU quem, derivado de {@code parentSpanId} — fato do trace, nao
     * suposicao. Passo A chama B quando algum evento de B aponta para um span de A.
     */
    private static List<PipelineEdge> callEdges(List<PipelineStep> steps) {
        Map<String, String> spanToStep = new LinkedHashMap<>();
        for (PipelineStep st : steps) {
            for (TraceEvent e : st.events()) {
                if (e.spanId() != null && !e.spanId().isBlank()) {
                    spanToStep.putIfAbsent(e.spanId(), st.id());
                }
            }
        }
        List<PipelineEdge> calls = new ArrayList<>();
        Set<String> vistos = new LinkedHashSet<>();
        for (PipelineStep st : steps) {
            for (TraceEvent e : st.events()) {
                String pai = e.parentSpanId();
                if (pai == null || pai.isBlank()) continue;
                String de = spanToStep.get(pai);
                if (de == null || de.equals(st.id())) continue;
                String chave = de + ">" + st.id();
                if (vistos.add(chave)) {
                    calls.add(PipelineEdge.call(de, st.id()));
                }
            }
        }
        return calls;
    }

    static boolean isTerminalMarker(TraceEvent e) {
        return "run.started".equals(e.name()) || "run.finished".equals(e.name());
    }

    static String stepKey(TraceEvent e) {
        Object harnessKind = e.meta().get("harnessKind");
        if (harnessKind != null && !String.valueOf(harnessKind).isBlank()) {
            return "harnessKind:" + harnessKind;
        }
        return "cc:" + e.category() + "/" + family(e.component());
    }

    /** Familia = trecho antes do primeiro ponto. {@code null} vira "?". */
    static String family(String component) {
        if (component == null || component.isBlank()) return "?";
        int dot = component.indexOf('.');
        return dot < 0 ? component : component.substring(0, dot);
    }

    private static PipelineStep toStep(String id, List<TraceEvent> evs) {
        TraceEvent first = evs.getFirst();
        String dominant = dominantComponent(evs);
        return new PipelineStep(
                id,
                first.category(),
                family(dominant),
                dominant,
                worstStatus(evs),
                maxDuration(evs),
                evs.stream().anyMatch(Pipeline::declaresFallback),
                first.seq(),
                evs.getLast().seq(),
                evs);
    }

    /*
     * Nao existe mais um booleano "externo" aqui. Ele dizia apenas "fora do processo
     * Python", mas a UI o exibia como "HTTP externo" e isso lia como "chamou a
     * internet" — Ollama e Qdrant rodam nesta maquina. Quem responde ONDE a coisa
     * roda agora e o ComponentCatalog, com rotulo honesto.
     */

    static boolean declaresFallback(TraceEvent e) {
        return Boolean.TRUE.equals(e.meta().get("fallbackTriggered"));
    }

    /** Component mais frequente do grupo; empate resolve pelo primeiro que apareceu. */
    static String dominantComponent(List<TraceEvent> evs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TraceEvent e : evs) {
            if (e.component() == null) continue;
            counts.merge(e.component(), 1, Integer::sum);
        }
        String best = null;
        int bestN = -1;
        for (Map.Entry<String, Integer> en : counts.entrySet()) {
            if (en.getValue() > bestN) {
                best = en.getKey();
                bestN = en.getValue();
            }
        }
        return best;
    }

    /**
     * Pior desfecho do grupo. Sem nenhum desfecho, mas com abertura, o passo esta
     * {@code running} — que e exatamente o estado que a UI precisa acender quando o
     * tempo real chegar. Sem nada, {@code null}.
     */
    static String worstStatus(List<TraceEvent> evs) {
        String worst = null;
        int worstRank = Integer.MAX_VALUE;
        boolean opened = false;
        for (TraceEvent e : evs) {
            if ("started".equals(e.status())) opened = true;
            // status e opcional no envelope, e List.of().indexOf(null) lanca NPE.
            if (e.status() == null) continue;
            int rank = OUTCOMES.indexOf(e.status());
            if (rank < 0) continue;
            if (rank < worstRank) {
                worstRank = rank;
                worst = e.status();
            }
        }
        if (worst != null) return worst;
        return opened ? RUNNING : null;
    }

    /** Maior medida do grupo. Sem nenhuma medida, {@code null} — nunca 0.0. */
    static Double maxDuration(List<TraceEvent> evs) {
        Double max = null;
        for (TraceEvent e : evs) {
            if (e.durationMs() == null) continue;
            if (max == null || e.durationMs() > max) max = e.durationMs();
        }
        return max;
    }
}
