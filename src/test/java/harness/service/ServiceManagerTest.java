package harness.service;

import inspector.domain.HealthProbe;
import inspector.domain.LaunchResult;
import inspector.domain.ServiceAction;
import inspector.domain.ServiceHealth;
import inspector.domain.ServiceLauncher;
import inspector.domain.ServiceTarget;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle de serviço — e a linha que separa "botão" de "watchdog".
 *
 * <p>O teste mais importante deste arquivo é
 * {@link #nadaSobeSozinhoQuandoNinguemPede()}: ele trava a lição do NOC. Se um dia
 * alguém acrescentar ressurreição automática, ele quebra.
 */
class ServiceManagerTest {

    /** Sonda de mentira: os serviços em {@code up} respondem, o resto não. */
    private static final class FakeProbe implements HealthProbe {
        final Set<String> up = new HashSet<>();
        int probes;

        @Override
        public ServiceHealth probe(final ServiceTarget target) {
            probes++;
            final var alive = up.contains(target.id());
            return new ServiceHealth(target.id(), target.label(), target.role(), alive,
                    alive ? 200 : null, alive ? 3L : null,
                    alive ? "ok" : "sem resposta", Instant.now().toString());
        }
    }

    /** Launcher de mentira: registra o que foi pedido e pode "subir" o serviço. */
    private static final class FakeLauncher implements ServiceLauncher {
        final List<String> ran = new ArrayList<>();
        final FakeProbe probe;
        boolean succeed = true;
        boolean becomesHealthy = true;

        FakeLauncher(final FakeProbe probe) {
            this.probe = probe;
        }

        @Override
        public LaunchResult run(final ServiceAction action) {
            ran.add(action.serviceId());
            if (succeed && becomesHealthy) probe.up.add(action.serviceId());
            return new LaunchResult(action.serviceId(), succeed, succeed ? 0 : 1,
                    succeed ? "ok" : "docker: daemon não está rodando", Instant.now().toString());
        }
    }

    private static final List<ServiceTarget> TARGETS = List.of(
            new ServiceTarget("ollama", "Ollama", URI.create("http://x/1"), "modelo"),
            new ServiceTarget("qdrant", "Qdrant", URI.create("http://x/2"), "vetores"),
            new ServiceTarget("gpt", "GPT-Docker", URI.create("http://x/3"), "oráculo"));

    private static Map<String, ServiceAction> actions() {
        Map<String, ServiceAction> m = new HashMap<>();
        for (final String id : List.of("ollama", "qdrant", "gpt")) {
            m.put(id, new ServiceAction(id, id, List.of("cmd", "/c", "echo", id), null));
        }
        return m;
    }

    private ServiceManager manager(final FakeProbe probe, final FakeLauncher launcher) {
        return new ServiceManager(TARGETS, actions(), probe, launcher, ms -> { }, 30);
    }

    @Test
    void statusRelataOquePerguntouSemSubirNada() {
        var probe = new FakeProbe();
        probe.up.add("ollama");
        var launcher = new FakeLauncher(probe);

        var health = manager(probe, launcher).status();

        assertEquals(3, health.size());
        assertTrue(health.get(0).up());
        assertTrue(launcher.ran.isEmpty(), "status OBSERVA, não age");
    }

    @Test
    void nadaSobeSozinhoQuandoNinguemPede() {
        var probe = new FakeProbe();
        var launcher = new FakeLauncher(probe);
        var mgr = manager(probe, launcher);

        mgr.status();
        mgr.snapshot();
        mgr.status("qdrant");

        assertTrue(launcher.ran.isEmpty(),
                "lição do NOC: nada de ressurreição automática — só ação de gente");
    }

    @Test
    void startSobeOquefaltaEesperaFicarPronto() {
        var probe = new FakeProbe();
        var launcher = new FakeLauncher(probe);

        var report = manager(probe, launcher).start("qdrant");

        assertTrue(report.up());
        assertFalse(report.alreadyUp());
        assertEquals(List.of("qdrant"), launcher.ran);
    }

    @Test
    void servicoJAnoArNaoEsubidoDeNovo() {
        var probe = new FakeProbe();
        probe.up.add("qdrant");
        var launcher = new FakeLauncher(probe);

        var report = manager(probe, launcher).start("qdrant");

        assertTrue(report.alreadyUp());
        assertTrue(launcher.ran.isEmpty(), "subir de novo geraria container duplicado");
    }

    @Test
    void comandoQueFalhaRelataOmotivoReal() {
        var probe = new FakeProbe();
        var launcher = new FakeLauncher(probe);
        launcher.succeed = false;

        var report = manager(probe, launcher).start("gpt");

        assertFalse(report.up());
        assertTrue(report.detail().contains("daemon não está rodando"));
    }

    @Test
    void comandoQueRodaMasNaoFicaPronoNAOeReportadoComoSucesso() {
        var probe = new FakeProbe();
        var launcher = new FakeLauncher(probe);
        launcher.becomesHealthy = false;

        var report = manager(probe, launcher).start("gpt");

        assertFalse(report.up(), "exit 0 não é prova de que o serviço respondeu");
        assertTrue(report.detail().contains("não respondeu a tempo"));
    }

    @Test
    void servicoSemAcaoDeclaradaNaoViraComando() {
        var probe = new FakeProbe();
        var launcher = new FakeLauncher(probe);
        var mgr = new ServiceManager(TARGETS, Map.of(), probe, launcher, ms -> { }, 30);

        var report = mgr.start("qdrant");

        assertFalse(report.up());
        assertTrue(report.detail().contains("não existe ação declarada"));
        assertTrue(launcher.ran.isEmpty());
    }

    @Test
    void startAllSobeSOoquefalta() {
        var probe = new FakeProbe();
        probe.up.add("ollama");
        var launcher = new FakeLauncher(probe);

        var reports = manager(probe, launcher).startAll();

        assertEquals(3, reports.size());
        assertEquals(List.of("qdrant", "gpt"), launcher.ran);
        assertTrue(reports.get(0).alreadyUp());
    }

    @Test
    void restartFailedSoMexeNoqueEstaForaDoAr() {
        var probe = new FakeProbe();
        probe.up.add("ollama");
        probe.up.add("gpt");
        var launcher = new FakeLauncher(probe);

        var reports = manager(probe, launcher).restartFailed();

        assertEquals(List.of("qdrant"), launcher.ran);
        assertEquals(1, reports.size());
    }

    @Test
    void snapshotResumeOestadoGeralParaAtela() {
        var probe = new FakeProbe();
        probe.up.add("ollama");
        var snap = manager(probe, new FakeLauncher(probe)).snapshot();

        assertEquals("AMBER", snap.get("overall"));
        assertEquals(1L, snap.get("upCount"));
        assertEquals(3, snap.get("serviceCount"));
    }

    @Test
    void tudoNoArEverde() {
        var probe = new FakeProbe();
        probe.up.addAll(List.of("ollama", "qdrant", "gpt"));
        assertEquals("GREEN", manager(probe, new FakeLauncher(probe)).snapshot().get("overall"));
    }

    @Test
    void tudoForaEvermelho() {
        var probe = new FakeProbe();
        assertEquals("RED", manager(probe, new FakeLauncher(probe)).snapshot().get("overall"));
    }

    @Test
    void servicoNaoDeclaradoNaoInventaSaude() {
        var probe = new FakeProbe();
        var h = manager(probe, new FakeLauncher(probe)).status("inexistente");
        assertFalse(h.up());
        assertTrue(h.detail().contains("não declarado"));
    }
}
