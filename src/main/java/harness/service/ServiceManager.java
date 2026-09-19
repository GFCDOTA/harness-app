package harness.service;

import inspector.domain.HealthProbe;
import inspector.domain.LaunchResult;
import inspector.domain.ServiceAction;
import inspector.domain.ServiceHealth;
import inspector.domain.ServiceLauncher;
import inspector.domain.ServiceTarget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Lifecycle das dependências — determinístico, e DISPARADO POR GENTE.
 *
 * <p>Esta classe resolve uma tensão real entre duas coisas que o Felipe pediu, e a
 * resolução está registrada porque ela não é óbvia:
 *
 * <ul>
 *   <li>a missão pede {@code start_all} e {@code restart_failed} no Harness;
 *   <li>a lição do NOC (removido em 2026-07-24) proíbe ressurreição AUTOMÁTICA —
 *       watchdog, Scheduled Task, respawn reagindo a "caiu".
 * </ul>
 *
 * <p>O que este código faz: uma pessoa aperta "Start All"; o Harness confere a
 * saúde, sobe SÓ o que está faltando, espera ficar pronto e relata o que deu certo
 * e o que não deu. O que ele NÃO faz: observar sozinho, reagir a queda, tentar de
 * novo. {@code restartFailed()} só existe como AÇÃO — nunca como reflexo.
 *
 * <p>O comando de cada serviço é lista FECHADA, declarada em código
 * ({@link ServiceRegistry}). Nada que venha da UI ou do modelo vira linha de
 * comando (missão §20).
 */
public final class ServiceManager {

    private final List<ServiceTarget> targets;
    private final Map<String, ServiceAction> actions;
    private final HealthProbe probe;
    private final ServiceLauncher launcher;
    private final LongConsumer sleeper;
    private final long readyTimeoutMs;

    /** Quanto esperar um serviço ficar pronto depois de mandar subir. */
    private static final long READY_TIMEOUT_MS = 60_000;
    private static final long READY_POLL_MS = 2_000;

    public ServiceManager(final List<ServiceTarget> targets, final Map<String, ServiceAction> actions,
                          final HealthProbe probe, final ServiceLauncher launcher) {
        this(targets, actions, probe, launcher, ServiceManager::sleepQuietly, READY_TIMEOUT_MS);
    }

    /**
     * Construtor de teste: sono E janela de espera injetados.
     *
     * <p>Injetar só o sono não bastava — a espera é medida em relógio de parede, e a
     * suíte levava 60 s reais provando que um serviço NÃO fica pronto. Teste lento é
     * teste que ninguém roda.
     */
    ServiceManager(final List<ServiceTarget> targets, final Map<String, ServiceAction> actions,
                   final HealthProbe probe, final ServiceLauncher launcher, final LongConsumer sleeper,
                   final long readyTimeoutMs) {
        this.targets = List.copyOf(targets);
        this.actions = Map.copyOf(actions);
        this.probe = probe;
        this.launcher = launcher;
        this.sleeper = sleeper;
        this.readyTimeoutMs = readyTimeoutMs;
    }

    public List<ServiceTarget> targets() {
        return this.targets;
    }

    /** Saúde de TODOS os serviços, agora. Só observa. */
    public List<ServiceHealth> status() {
        final var out = new ArrayList<ServiceHealth>(this.targets.size());
        for (final var target : this.targets) {
            out.add(this.probe.probe(target));
        }
        return out;
    }

    public ServiceHealth status(final String serviceId) {
        return this.targets.stream().filter(target -> target.id().equals(serviceId)).findFirst()
                .map(this.probe::probe)
                .orElseGet(() -> new ServiceHealth(serviceId, serviceId, "desconhecido",
                        false, null, null, "serviço não declarado", Instant.now().toString()));
    }

    /**
     * Sobe UM serviço e espera ficar pronto.
     *
     * <p>Já estando no ar, não faz nada — subir de novo o que já responde é a
     * receita de container duplicado.
     */
    public StartReport start(final String serviceId) {
        final var action = this.actions.get(serviceId);
        if (action == null) {
            return StartReport.notDeclared(serviceId);
        }
        final var before = status(serviceId);
        if (before.up()) {
            return new StartReport(serviceId, true, true, null, before,
                    "já estava no ar");
        }
        final var result = this.launcher.run(action);
        if (!result.ok()) {
            return new StartReport(serviceId, false, false, result, before,
                    "o comando de start falhou: " + firstLine(result.output()));
        }
        final var after = waitUntilReady(serviceId);
        return new StartReport(serviceId, after.up(), false, result, after,
                after.up() ? "no ar" : "o comando rodou, mas o serviço não respondeu a tempo: "
                        + after.detail());
    }

    /**
     * Sobe tudo que está faltando, uma vez.
     *
     * <p>Sequencial de propósito: Qdrant e GPT-Docker dependem do Docker Desktop, e
     * disparar tudo em paralelo esconderia a causa real de uma falha em cascata.
     */
    public List<StartReport> startAll() {
        final var reports = new ArrayList<StartReport>();
        for (final var target : this.targets) {
            if (!this.actions.containsKey(target.id())) continue;
            reports.add(start(target.id()));
        }
        return reports;
    }

    /**
     * Reinicia SÓ o que está fora do ar.
     *
     * <p>É ação, não reflexo: alguém apertou o botão. Não existe nada neste código
     * que chame isto sozinho, e é isso que separa esta classe do NOC.
     */
    public List<StartReport> restartFailed() {
        final var reports = new ArrayList<StartReport>();
        for (final var health : status()) {
            if (!health.up() && this.actions.containsKey(health.id())) {
                reports.add(start(health.id()));
            }
        }
        return reports;
    }

    /** Um retrato só, pronto para a UI. */
    public Map<String, Object> snapshot() {
        final var health = status();
        final var up = health.stream().filter(ServiceHealth::up).count();
        final var out = new LinkedHashMap<String, Object>();
        out.put("services", health);
        out.put("upCount", up);
        out.put("serviceCount", health.size());
        out.put("startable", List.copyOf(this.actions.keySet()));
        out.put("overall", up == health.size() ? "GREEN" : up == 0 ? "RED" : "AMBER");
        return out;
    }

    private ServiceHealth waitUntilReady(final String serviceId) {
        final var deadline = System.currentTimeMillis() + this.readyTimeoutMs;
        var last = status(serviceId);
        while (!last.up() && System.currentTimeMillis() < deadline) {
            this.sleeper.accept(READY_POLL_MS);
            last = status(serviceId);
        }
        return last;
    }

    private static void sleepQuietly(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String firstLine(final String text) {
        if (text == null || text.isBlank()) return "sem saída";
        final var newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /** O que aconteceu ao tentar subir um serviço. */
    public record StartReport(String serviceId, boolean up, boolean alreadyUp,
                              LaunchResult launch, ServiceHealth health, String detail) {

        static StartReport notDeclared(final String serviceId) {
            return new StartReport(serviceId, false, false, null, null,
                    "não existe ação declarada para '" + serviceId + "'");
        }
    }
}
