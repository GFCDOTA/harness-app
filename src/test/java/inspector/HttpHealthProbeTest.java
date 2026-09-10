package inspector;

import inspector.domain.ServiceHealth;
import inspector.domain.ServiceTarget;
import inspector.source.HttpHealthProbe;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class HttpHealthProbeTest {

    /** Porta fechada e deterministico e instantaneo em loopback — nada de rede real. */
    private static int portaFechada() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void servicoForaDoArNaoVemComoNoAr() throws Exception {
        ServiceTarget alvo = new ServiceTarget("x", "X",
                URI.create("http://127.0.0.1:" + portaFechada() + "/health"), "teste");
        ServiceHealth h = new HttpHealthProbe().probe(alvo);
        assertFalse(h.up(), "conexao recusada nao pode virar 'no ar'");
        assertNull(h.httpStatus());
        assertNotNull(h.detail());
        assertFalse(h.detail().isBlank(), "o painel precisa dizer POR QUE esta fora");
        assertNotNull(h.checkedAt());
    }

    @Test
    void preservaIdentidadeDoAlvoNaResposta() throws Exception {
        ServiceTarget alvo = new ServiceTarget("qdrant", "Qdrant",
                URI.create("http://127.0.0.1:" + portaFechada() + "/collections"), "banco vetorial");
        ServiceHealth h = new HttpHealthProbe().probe(alvo);
        assertEquals("qdrant", h.id());
        assertEquals("Qdrant", h.label());
        assertEquals("banco vetorial", h.role());
    }

    @Test
    void mediaLatenciaMesmoQuandoFalha() throws Exception {
        ServiceHealth h = new HttpHealthProbe().probe(new ServiceTarget("x", "X",
                URI.create("http://127.0.0.1:" + portaFechada() + "/"), "teste"));
        assertNotNull(h.latencyMs());
        assertTrue(h.latencyMs() >= 0);
    }

    @Test
    void alvoSemUriEhRecusadoNaConstrucao() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceTarget("x", "X", null, "teste"));
    }
}
