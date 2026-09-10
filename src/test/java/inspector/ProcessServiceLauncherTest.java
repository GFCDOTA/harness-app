package inspector;

import inspector.domain.LaunchResult;
import inspector.domain.ServiceAction;
import inspector.source.ProcessServiceLauncher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessServiceLauncherTest {

    private final ProcessServiceLauncher launcher = new ProcessServiceLauncher();

    @Test
    void comandoQueFuncionaVoltaOkComSaida() {
        LaunchResult r = launcher.run(new ServiceAction("eco", "Eco",
                List.of("cmd", "/c", "echo", "subiu"), null));
        assertTrue(r.ok(), r.output());
        assertEquals(0, r.exitCode());
        assertTrue(r.output().contains("subiu"), r.output());
    }

    @Test
    void comandoInexistenteFalhaDizendoPorQue() {
        LaunchResult r = launcher.run(new ServiceAction("fantasma", "Fantasma",
                List.of("binario_que_nao_existe_12345"), null));
        assertFalse(r.ok());
        assertNull(r.exitCode());
        assertFalse(r.output().isBlank(), "falha muda nao ajuda ninguem");
    }

    @Test
    void exitCodeDiferenteDeZeroNaoEhSucesso() {
        LaunchResult r = launcher.run(new ServiceAction("ruim", "Ruim",
                List.of("cmd", "/c", "exit", "3"), null));
        assertFalse(r.ok());
        assertEquals(3, r.exitCode());
    }

    @Test
    void acaoSemComandoEhRecusadaNaConstrucao() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceAction("x", "X", List.of(), null));
    }

    @Test
    void comandoFicaImutavelDepoisDeConstruido() {
        ServiceAction a = new ServiceAction("x", "X", new java.util.ArrayList<>(List.of("cmd")), null);
        assertThrows(UnsupportedOperationException.class, () -> a.command().add("/c"));
    }

    @Test
    void serviceIdEhObrigatorioNoResultado() {
        assertThrows(IllegalArgumentException.class,
                () -> new LaunchResult("  ", true, 0, "", "2026-01-01T00:00:00Z"));
    }
}
