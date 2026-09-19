package inspector.domain;

/**
 * Ligação entre dois passos, com o TIPO explícito.
 *
 * <p>O tipo importa para não ensinar arquitetura errada. Uma seta de
 * {@code reference_db.retrieve} para o Ollama significa <b>houve chamada de verdade
 * nesta execução</b>; uma seta para {@code tools/reference_db.py::retrieve} significa
 * <b>essa é a implementação daquele componente</b> — coisas diferentes que, desenhadas
 * igual, viram a mesma mentira.
 *
 * <p>{@link #SEQUENCE} e {@link #CALL} saem do trace ({@code seq} e
 * {@code parentSpanId}). {@link #IMPLEMENTS} sai do catálogo verificado no código.
 */
public record PipelineEdge(String id, String from, String to, String kind) {

    /** O passo seguinte na linha do tempo. */
    public static final String SEQUENCE = "sequence";
    /** Chamada real, provada por {@code parentSpanId}. */
    public static final String CALL = "call";
    /** O pipeline desviou porque algo falhou. */
    public static final String FALLBACK = "fallback";
    /** Não é execução: é o código que implementa, ou a lib que ele usa. */
    public static final String IMPLEMENTS = "implements";

    public static PipelineEdge sequence(final String from, final String to) {
        return new PipelineEdge(from + "->" + to, from, to, SEQUENCE);
    }

    public static PipelineEdge fallback(final String from, final String to) {
        return new PipelineEdge(from + "->" + to + ":fallback", from, to, FALLBACK);
    }

    public static PipelineEdge call(final String from, final String to) {
        return new PipelineEdge(from + "->" + to + ":call", from, to, CALL);
    }

    public boolean isFallback() {
        return FALLBACK.equals(kind);
    }
}
