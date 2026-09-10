package inspector;

import inspector.source.SseTraceSource;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A costura do SSE existe, e este teste e o que impede ela de ser esquecida em
 * silencio: se alguem implementar, o teste quebra e obriga a atualizar o contrato.
 */
class SseTraceSourceTest {

    private static final URI EP = URI.create("http://127.0.0.1:8788/api/trace/stream?runId=r1");

    @Test
    void falhaAltoEmVezDeFingirQueLeuAoVivo() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> new SseTraceSource(EP).stream(e -> { }));
        assertTrue(ex.getMessage().contains("fase do transporte"), ex.getMessage());
    }

    @Test
    void describeDizHonestamenteQueNaoEstaImplementado() {
        String d = new SseTraceSource(EP).describe();
        assertTrue(d.contains("nao implementado") || d.contains("implementado"), d);
        assertTrue(d.contains("8788"), "a UI precisa poder mostrar o endpoint previsto");
    }

    @Test
    void guardaOEndpointRecebido() {
        assertEquals(EP, new SseTraceSource(EP).endpoint());
    }
}
