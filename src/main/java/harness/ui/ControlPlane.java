package harness.ui;

import harness.agent.domain.AgentOutcome;
import harness.agent.domain.AgentRuntime;
import harness.agent.domain.AgentState;
import harness.agent.domain.AgentTrace;
import harness.agent.domain.LlmPlanner;
import harness.agent.domain.ToolRegistry;
import harness.agent.source.JsonlTraceRecorder;
import harness.agent.source.OllamaPlanner;
import harness.agent.source.StdioCapabilityHost;
import harness.config.HarnessConfig;
import harness.projection.AgentProjection;
import harness.service.ServiceManager;
import harness.service.ServiceRegistry;
import inspector.source.HttpHealthProbe;
import inspector.source.ProcessServiceLauncher;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A raiz de composição do CONTROL PLANE — o que faz o Harness deixar de observar e
 * passar a operar.
 *
 * <p>Vive fora de {@code InspectorApp} de propósito: o Inspector é o plano de
 * OBSERVABILIDADE (lê trace, projeta, desenha), e misturar as duas coisas numa
 * classe só deixaria a raiz de composição com duas razões para mudar.
 *
 * <p>Ciclo de vida: o capability host é PROCESSO FILHO e morre junto. Nada aqui
 * vigia, repete ou ressuscita — a regra do NOC vale para o agente também.
 */
public final class ControlPlane implements AutoCloseable {

    private final HarnessConfig config;
    private final AgentState state;
    private final AgentProjection projection = new AgentProjection();
    private final ServiceManager services;
    private final LlmPlanner planner;

    private StdioCapabilityHost host;
    private ToolRegistry registry;
    private AgentOutcome lastOutcome;
    private String hostDetail = "não iniciado";

    public ControlPlane(final HarnessConfig config) {
        this.config = config;
        this.state = new AgentState(config.project());
        this.planner = new OllamaPlanner(config.ollamaUrl(), config.agentModel(),
                config.agentTimeout());
        this.services = new ServiceManager(
                ServiceRegistry.targets(config), ServiceRegistry.actions(config),
                new HttpHealthProbe(), new ProcessServiceLauncher());
    }

    public ServiceManager services() {
        return this.services;
    }

    public AgentState state() {
        return this.state;
    }

    /**
     * Sobe o capability host e lê a tabela de capabilities.
     *
     * <p>Falhar aqui NÃO derruba o app: o Inspector continua funcionando e a aba do
     * agente mostra o motivo real. Arquitetura all-or-nothing é o que a missão §42
     * proíbe.
     */
    public boolean start() {
        this.host = new StdioCapabilityHost(
                this.config.capabilityHostCommand(),
                new File(this.config.capabilityHostDir().toString()),
                this.config.capabilityHostEnv(),
                30_000);
        final var ok = this.host.start();
        if (ok) {
            this.registry = new ToolRegistry(this.host.describeTools(), this.host.unsupported());
            this.hostDetail = this.registry.all().size() + " capabilities";
        } else {
            this.registry = new ToolRegistry(List.of(), List.of());
            this.hostDetail = this.host.startupError() == null
                    ? "o capability host não subiu" : this.host.startupError();
        }
        return ok;
    }

    public boolean hostUp() {
        return this.host != null && this.host.isAlive();
    }

    /**
     * Executa um comando em linguagem natural.
     *
     * <p>Cada comando é uma RUN com trace próprio, gravado no mesmo diretório que o
     * Inspector lê — o que acabou de acontecer pode ser aberto na Pipeline View.
     */
    public AgentOutcome command(final String text) {
        final var runId = "cmd_" + Instant.now().toString()
                .replace(":", "").replace("-", "").replace(".", "_");
        final var recorder = new JsonlTraceRecorder(this.config.traceDir(), runId);
        final var trace = new AgentTrace(runId, recorder);
        final var runtime = new AgentRuntime(
                this.host, this.planner, this.registry, this.state,
                this.config.maxAgentAttempts(), this.config.autoApproveHighRisk());
        this.lastOutcome = runtime.execute(text, trace);
        return this.lastOutcome;
    }

    /** O retrato que a UI mostra. Sonda o modelo, então não é de graça — chame por evento. */
    public String agentJson() {
        return this.projection.toJson(this.projection.snapshot(
                this.state, this.registry, this.lastOutcome,
                this.config.agentModel(), this.planner.isAvailable(),
                hostUp(), this.hostDetail));
    }

    /** Idem, sem sondar o modelo: para atualizar a tela depois de um comando. */
    public String agentJson(final boolean modelUp) {
        return this.projection.toJson(this.projection.snapshot(
                this.state, this.registry, this.lastOutcome,
                this.config.agentModel(), modelUp, hostUp(), this.hostDetail));
    }

    public Map<String, Object> serviceSnapshot() {
        return this.services.snapshot();
    }

    @Override
    public void close() {
        if (this.host != null) this.host.close();
    }
}
