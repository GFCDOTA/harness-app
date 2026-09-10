package inspector.domain;

/**
 * Ligação entre dois passos. {@code fallback} marca o desvio: o pipeline nao seguiu
 * pelo caminho pretendido. É derivado de {@code meta.fallbackTriggered}, nao de
 * conhecimento embutido sobre qual componente costuma falhar.
 */
public record PipelineEdge(String id, String from, String to, boolean fallback) {

    public static PipelineEdge sequence(String from, String to) {
        return new PipelineEdge(from + "->" + to, from, to, false);
    }

    public static PipelineEdge fallback(String from, String to) {
        return new PipelineEdge(from + "->" + to + ":fallback", from, to, true);
    }
}
