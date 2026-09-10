package inspector.source;

import inspector.domain.TraceEvent;
import inspector.domain.TraceSource;

import java.net.URI;
import java.util.function.Consumer;

/**
 * ADAPTER — trace AO VIVO por SSE. Costura preparada, implementação na fase seguinte.
 *
 * <p>Existe agora para provar que o port {@link TraceSource} tem duas implementações
 * reais e que o domínio/UI não precisam mudar quando o tempo real chegar: muda só
 * este adapter. Falha ALTO e explícito em vez de fingir que funciona — degradar em
 * silêncio é o pecado que o {@code rag.degraded} do próprio trace ensina a evitar.
 *
 * <p>Contrato previsto: {@code GET /api/trace/stream?runId=…}, {@code Last-Event-ID}
 * = {@code seq} para reconnect.
 */
public final class SseTraceSource implements TraceSource {

    private final URI endpoint;

    public SseTraceSource(URI endpoint) {
        this.endpoint = endpoint;
    }

    public URI endpoint() {
        return endpoint;
    }

    @Override
    public String describe() {
        return "sse-live (não implementado): " + endpoint;
    }

    @Override
    public void stream(Consumer<TraceEvent> sink) {
        throw new UnsupportedOperationException(
                "SseTraceSource chega na fase do transporte; hoje a fonte é JsonlReplayTraceSource. "
                        + "Endpoint previsto: " + endpoint);
    }
}
