package inspector.ui;

import harness.config.HarnessConfig;
import harness.ui.ControlPlane;
import inspector.domain.HealthProbe;
import inspector.domain.LaunchResult;
import inspector.domain.ServiceAction;
import inspector.domain.Run;
import inspector.domain.TraceEvent;
import inspector.projection.OracleProjection;
import inspector.projection.TraceProjection;
import inspector.source.GptConsultLog;
import inspector.source.HttpHealthProbe;
import inspector.source.JsonlReplayTraceSource;
import inspector.source.SourceVerifier;
import inspector.source.TraceLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Host desktop do AI Pipeline Inspector (ADR-001).
 *
 * <p>O que ele é: uma janela nativa que hospeda a UI React e lhe entrega JSON.
 * <p>O que ele NÃO é: um servidor, um supervisor, um watchdog. Ele <b>observa</b>.
 * Fechar a janela mata o processo — não existe {@code System.exit} aqui, o
 * encerramento é o do próprio toolkit.
 *
 * <p>Uso: {@code mvnw javafx:run} · trace alternativo: {@code -Dtrace=<caminho>} ·
 * smoke check não-interativo: {@code -Dselftest=true}.
 */
public final class InspectorApp extends Application {

    /**
     * Convenção do app: um diretório {@code traces-local/} ao lado dele. É
     * gitignored — trace é dado de execução, não fonte. Se não existir, o
     * TraceLocator exige -Dtrace / -DtraceDir / INSPECTOR_TRACE_DIR em vez de
     * abrir vazio fingindo normalidade.
     */
    private static final Path CONVENTIONAL_TRACE_DIR = Paths.get("traces-local");

    /**
     * Onde vive o código do pipeline, para conferir o que o catálogo afirma. Ausente
     * não é erro: a UI passa a dizer "não verificado" em vez de afirmar.
     */
    private static SourceVerifier resolvePipelineRepo() {
        final var prop = System.getProperty("pipelineRepo");
        if (prop != null && !prop.isBlank()) return new SourceVerifier(Paths.get(prop.trim()));
        final var env = System.getenv("INSPECTOR_PIPELINE_REPO");
        if (env != null && !env.isBlank()) return new SourceVerifier(Paths.get(env.trim()));
        return new SourceVerifier(Paths.get("..", "sketchup-mcp"));
    }

    private final TraceProjection projection = new TraceProjection(resolvePipelineRepo());
    private final OracleProjection oracleProjection = new OracleProjection();
    private final HealthProbe healthProbe = new HttpHealthProbe();

    /**
     * A esteira desta máquina — agora vinda do {@link ControlPlane}, que a monta a
     * partir da configuração central ({@code harness.json}).
     *
     * <p>Antes era constante aqui. Deixou de ser porque a URL de cada serviço passou
     * a ser configuração de UM lugar só, lida também pelo capability host em Python.
     * Duas listas de portas divergem em silêncio; uma, não.
     */
    private final HarnessConfig config = HarnessConfig.load();
    private final ControlPlane controlPlane = new ControlPlane(this.config);

    private static final int HEALTH_POLL_SECONDS = 5;

    /**
     * O que o Felipe pode LIGAR pelo app, por clique.
     *
     * <p>Lista fechada e montada no codigo: a pagina so manda um id conhecido, nunca
     * um comando. Sem isso, um painel web viraria um shell.
     *
     * <p>E nao ha entrada aqui para "reiniciar se cair" — ver {@link ServiceAction}.
     * Botao sim, watchdog nunca; foi o watchdog que matou o NOC.
     *
     * <p>Caminhos desta maquina, como os de {@link #SERVICES}. Nao usa
     * {@code powershell -ExecutionPolicy Bypass}: foi o padrao que o Defender flagou.
     */
    private static final int DRAIN_POLL_MILLIS = 400;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Thread de TRABALHO do agente, separada da de sondagem.
     *
     * <p>Um comando pode levar dezenas de segundos (o modelo pensa, o gate mede) e
     * não pode ficar atrás de um health check na fila — nem, muito menos, na thread
     * da UI. DAEMON pela mesma regra de sempre: fechar a janela mata.
     */
    private final ExecutorService agentPool = Executors.newSingleThreadExecutor(r -> {
        final var t = new Thread(r, "agent-runtime");
        t.setDaemon(true);
        return t;
    });

    /**
     * Sondas FORA da thread da UI: 3 serviços x 2 s de timeout congelariam a janela.
     * DAEMON de propósito — thread viva seguraria o processo depois de fechar a
     * janela, e a regra desta casa é que fechar mata.
     */
    private final ExecutorService probePool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "health-probe");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void start(final Stage stage) {
        final var selfTest = Boolean.getBoolean("selftest");
        // Trace AUSENTE deixou de ser fatal: o Harness agora OPERA a planta, e
        // travar a janela inteira porque ainda não há um replay deixaria o comando
        // inacessível justamente numa máquina recém-configurada.
        JsonlReplayTraceSource source;
        try {
            source = resolveSource();
        } catch (final RuntimeException ex) {
            System.err.println("[harness] sem trace para abrir: " + ex.getMessage());
            source = null;
        }
        final var traceSource = source;

        final var view = new WebView();
        final var engine = view.getEngine();
        final var bridge = new WebBridge(engine);

        engine.setOnError(ev -> System.err.println("[inspector] webview: " + ev.getMessage()));

        stage.setTitle("Harness — " + (traceSource == null ? "control plane" : traceSource.describe()));
        stage.setScene(new Scene(new BorderPane(view), 900, 760));
        stage.show();

        final var page = InspectorApp.class.getResource("/web/index.html");
        if (page == null) {
            throw new IllegalStateException("recurso /web/index.html não empacotado");
        }

        engine.getLoadWorker().stateProperty().addListener((obs, old, now) -> {
            if (now == Worker.State.SUCCEEDED) {
                whenReady(bridge, 0, () -> {
                    if (traceSource != null) {
                        bridge.loadRun(this.projection.toJson(read(traceSource),
                                traceSource.describe()));
                    }
                    startControlPlane(bridge);
                    startOraclePolling(bridge);
                    if (selfTest) selfTest(bridge, stage);
                });
            } else if (now == Worker.State.FAILED) {
                System.err.println("[inspector] falhou carregar a UI");
                stage.close();
            }
        });
        engine.load(page.toExternalForm());
    }

    /**
     * Sonda a esteira a cada {@value #HEALTH_POLL_SECONDS} s e relê as consultas ao
     * GPT, empurrando tudo para o painel do oráculo.
     *
     * <p>Isto OBSERVA. Não sobe, não derruba, não reinicia nada — serviço fora do ar
     * aparece como fora do ar, e é o Docker (ou o serviço do Ollama) que ressuscita.
     */
    private void startOraclePolling(final WebBridge bridge) {
        final var consultLog = resolveConsultLog();
        final Runnable tick = () -> this.probePool.submit(() -> {
            final var health = this.controlPlane.services().status();
            final String json;
            try {
                json = this.oracleProjection.toJson(health, consultLog.readAll(),
                        consultLog.dir().toString());
            } catch (final RuntimeException ex) {
                System.err.println("[inspector] painel do oráculo falhou: " + ex.getMessage());
                return;
            }
            Platform.runLater(() -> {
                try {
                    bridge.setOracle(json);
                } catch (final RuntimeException ex) {
                    System.err.println("[inspector] não consegui empurrar o oráculo: " + ex.getMessage());
                }
            });
        });

        tick.run();
        final var poll = new Timeline(new KeyFrame(
                Duration.seconds(HEALTH_POLL_SECONDS), e -> tick.run()));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();

        startRequestDraining(bridge);
    }

    /**
     * Sobe o capability host e manda o primeiro retrato do agente para a tela.
     *
     * <p>Fora da thread da UI: subir um processo filho e ler a tabela de capabilities
     * leva segundos, e a janela não pode congelar por causa disso. Falhar aqui não
     * derruba nada — a aba do agente passa a mostrar o motivo.
     */
    private void startControlPlane(final WebBridge bridge) {
        this.agentPool.submit(() -> {
            try {
                final var ok = this.controlPlane.start();
                System.out.println("[harness] capability host: " + (ok ? "no ar" : "FORA"));
            } catch (final RuntimeException ex) {
                System.err.println("[harness] control plane falhou ao subir: " + ex.getMessage());
            }
            pushAgent(bridge);
        });
    }

    private void pushAgent(final WebBridge bridge) {
        final String json;
        try {
            json = this.controlPlane.agentJson();
        } catch (final RuntimeException ex) {
            System.err.println("[harness] painel do agente falhou: " + ex.getMessage());
            return;
        }
        Platform.runLater(() -> {
            try {
                bridge.setAgent(json);
            } catch (final RuntimeException ex) {
                System.err.println("[harness] não consegui empurrar o agente: " + ex.getMessage());
            }
        });
    }

    /**
     * Puxa os pedidos que a UI enfileirou e executa CADA UM uma vez.
     *
     * <p>Sem retentativa e sem reagir a estado: se o servico continuar fora do ar, o
     * painel mostra fora do ar e a decisao de tentar de novo e do Felipe.
     */
    private void startRequestDraining(final WebBridge bridge) {
        Timeline drain = new Timeline(new KeyFrame(Duration.millis(DRAIN_POLL_MILLIS), e -> {
            String pending;
            try {
                pending = bridge.drainRequests();
            } catch (final RuntimeException ex) {
                return;
            }
            if (pending == null || pending.isBlank() || pending.equals("[]")) return;

            final List<String> raw;
            try {
                raw = this.mapper.readValue(pending,
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            } catch (final Exception ex) {
                System.err.println("[harness] fila de pedidos ilegivel: " + ex.getMessage());
                return;
            }
            for (final var entry : raw) {
                dispatch(bridge, entry);
            }
        }));
        drain.setCycleCount(Timeline.INDEFINITE);
        drain.play();
    }

    /**
     * Despacha UM pedido da UI.
     *
     * <p>A fila passou a carregar objetos com {@code kind} porque agora há mais de um
     * tipo de pedido. O que NÃO mudou é a regra que importa: a página manda um id ou
     * um texto, nunca um comando de sistema. Quem decide o que roda é este código.
     */
    private void dispatch(final WebBridge bridge, final String entry) {
        final com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = this.mapper.readTree(entry);
        } catch (final Exception ex) {
            System.err.println("[harness] pedido ilegivel: " + entry);
            return;
        }
        final var kind = node.path("kind").asText("");
        switch (kind) {
            case "service" -> startService(bridge, node.path("id").asText(""));
            case "services" -> batchServices(bridge, node.path("action").asText(""));
            case "command" -> runCommand(bridge, node.path("text").asText(""));
            default -> System.err.println("[harness] pedido de tipo desconhecido: " + kind);
        }
    }

    private void startService(final WebBridge bridge, final String id) {
        this.probePool.submit(() -> {
            final var report = this.controlPlane.services().start(id);
            final var result = report.launch() != null ? report.launch()
                    : new LaunchResult(id, report.up(), null, report.detail(),
                            java.time.Instant.now().toString());
            Platform.runLater(() -> push(bridge, result));
        });
    }

    /**
     * "Subir tudo" e "reiniciar o que caiu" — por CLIQUE, nunca por detecção.
     *
     * <p>É a leitura que concilia a missão (§21 pede start_all/restart_failed) com a
     * lição do NOC: o que matou o NOC foi ressurreição automática, não um botão.
     */
    private void batchServices(final WebBridge bridge, final String action) {
        this.probePool.submit(() -> {
            final var reports = switch (action) {
                case "startAll" -> this.controlPlane.services().startAll();
                case "restartFailed" -> this.controlPlane.services().restartFailed();
                default -> List.<harness.service.ServiceManager.StartReport>of();
            };
            final var summary = reports.isEmpty()
                    ? "nada a fazer"
                    : reports.stream().map(r -> r.serviceId() + ": " + r.detail())
                            .reduce((a, b) -> a + "\n" + b).orElse("");
            Platform.runLater(() -> push(bridge, new LaunchResult(action, true, null, summary,
                    java.time.Instant.now().toString())));
        });
    }

    /** Um comando do Felipe. Thread própria: o modelo pensa, o gate mede, leva tempo. */
    private void runCommand(final WebBridge bridge, final String text) {
        if (text == null || text.isBlank()) return;
        this.agentPool.submit(() -> {
            try {
                this.controlPlane.command(text);
            } catch (final RuntimeException ex) {
                System.err.println("[harness] comando falhou: " + ex);
            }
            pushAgent(bridge);
        });
    }

    private void push(final WebBridge bridge, final LaunchResult r) {
        try {
            bridge.setLaunchResult(this.mapper.writeValueAsString(r));
        } catch (final Exception ex) {
            System.err.println("[inspector] nao consegui devolver o resultado: " + ex.getMessage());
        }
    }

    /** Mesmo padrão do trace: explícito, env, convenção — e vazio em vez de inventar. */
    private GptConsultLog resolveConsultLog() {
        final var prop = System.getProperty("consultsDir");
        if (prop != null && !prop.isBlank()) return new GptConsultLog(Paths.get(prop.trim()));
        final var env = System.getenv("INSPECTOR_CONSULTS_DIR");
        if (env != null && !env.isBlank()) return new GptConsultLog(Paths.get(env.trim()));
        return new GptConsultLog(Paths.get("consults-local"));
    }

    /** O domínio é montado a partir do port, nunca do arquivo direto. */
    private Run read(final JsonlReplayTraceSource source) {
        List<TraceEvent> collected = new ArrayList<>();
        source.stream(collected::add);
        return Run.fromEvents(collected);
    }

    /** A regra de resolução vive no TraceLocator, que é testável sem tela. */
    private JsonlReplayTraceSource resolveSource() {
        return TraceLocator.fromEnvironment(CONVENTIONAL_TRACE_DIR);
    }

    /** A UI registra a API de forma assíncrona; espera o flag em vez de chutar um sleep. */
    private void whenReady(final WebBridge bridge, final int attempt, final Runnable then) {
        boolean ready;
        try {
            ready = bridge.isReady();
        } catch (final RuntimeException ex) {
            ready = false;
        }
        if (ready) {
            then.run();
            return;
        }
        if (attempt >= 40) {
            System.err.println("[inspector] a UI não registrou window.inspector em 10s");
            return;
        }
        new Timeline(new KeyFrame(Duration.millis(250),
                e -> whenReady(bridge, attempt + 1, then))).play();
    }

    /**
     * Smoke check nao-interativo. Prova por MEDIDA do DOM, nao por captura de tela:
     * janela nativa nao e screenshotavel neste ambiente. Percorre as tres coisas que
     * podem quebrar de forma silenciosa: o grafo, o clique->painel, e a Events View.
     */
    private void selfTest(final WebBridge bridge, final Stage stage) {
        List<Passo> roteiro = List.of(
                new Passo(900, () -> {
                    System.out.println("[selftest] pipeline " + bridge.probe());
                    snapshot(stage, "01-pipeline");
                }),
                new Passo(400, () -> bridge.exec("document.querySelectorAll('.sn-explore')[0].click()")),
                new Passo(700, () -> {
                    System.out.println("[selftest] expansao " + bridge.probe());
                    snapshot(stage, "01b-expansao");
                }),
                new Passo(300, () -> bridge.exec("document.querySelectorAll('.sn-explore')[0].click()")),
                new Passo(500, () -> bridge.exec("document.querySelectorAll('.react-flow__node-step')[3]"
                        + ".dispatchEvent(new MouseEvent('click',{bubbles:true}))")),
                new Passo(500, () -> bridge.exec(
                        "document.querySelectorAll('.dt-evhead').forEach(function(final b){b.click();})")),
                new Passo(500, () -> {
                    System.out.println("[selftest] detalhe  " + bridge.probe());
                    snapshot(stage, "02-detalhe");
                }),
                new Passo(400, () -> bridge.exec("document.querySelectorAll('.dt-tab')[1].click()")),
                new Passo(500, () -> {
                    System.out.println("[selftest] implem  " + bridge.probe());
                    snapshot(stage, "02b-implementacao");
                }),
                new Passo(400, () -> bridge.exec("window.inspector.setView('oracle')")),
                new Passo(600, () -> {
                    System.out.println("[selftest] oraculo  " + bridge.probe());
                    snapshot(stage, "03-oraculo");
                }),
                new Passo(400, () -> bridge.exec("window.inspector.setView('events')")),
                new Passo(400, () -> System.out.println("[selftest] events   " + bridge.probe())),
                // O control plane sobe numa thread propria e leva alguns segundos;
                // fotografar antes disso registraria a tela vazia como se fosse o
                // estado normal.
                new Passo(400, () -> bridge.exec("window.inspector.setView('agent')")),
                new Passo(4000, () -> {
                    System.out.println("[selftest] agente   " + bridge.probe());
                    snapshot(stage, "05-agente");
                    launchStepOrClose(bridge, stage);
                }));
        executar(roteiro, 0);
    }

    /** Um passo do roteiro: espera, faz. */
    private record Passo(int delayMs, Runnable acao) {
    }

    /**
     * Roda o roteiro em sequencia, por indice.
     *
     * <p>Antes isto era uma cadeia de lambdas aninhadas e cada passo novo virava um
     * exercicio de contar parentese — que quebrou o build. Lista + indice nao tem
     * esse problema.
     */
    private void executar(final List<Passo> roteiro, final int i) {
        if (i >= roteiro.size()) return;
        Passo p = roteiro.get(i);
        new Timeline(new KeyFrame(Duration.millis(p.delayMs()), e -> {
            p.acao().run();
            executar(roteiro, i + 1);
        })).play();
    }

    /**
     * Passo OPT-IN do smoke check: exercita o caminho completo do botao
     * (UI enfileira -> Java puxa -> comando roda). Fica fora do padrao porque tem
     * efeito colateral real — sobe container —, e teste que liga coisa sem pedir e
     * exatamente o tipo de surpresa que este app promete nao dar.
     */
    private void launchStepOrClose(final WebBridge bridge, final Stage stage) {
        final var svc = System.getProperty("selftestLaunch");
        if (svc == null || svc.isBlank()) {
            stage.close();
            return;
        }
        System.out.println("[selftest] pedindo start de '" + svc + "' pela UI");
        bridge.exec("window.inspector.setView('oracle'); window.inspector.requestStart('"
                + svc.replace("'", "") + "')");
        executar(List.of(new Passo(60000, () -> {
            System.out.println("[selftest] launch   " + bridge.probe());
            snapshot(stage, "04-launch");
            stage.close();
        })), 0);
    }

    /**
     * Fotografa a janela em PNG quando {@code -DsnapshotDir} está setado.
     *
     * <p>Existe porque a janela nativa não é capturável de fora neste ambiente, e sem
     * imagem não dá para pedir revisão visual a ninguém. O app fotografa a si mesmo.
     * Sem a propriedade, não faz nada — não é caminho de dados.
     */
    private void snapshot(final Stage stage, final String name) {
        final var dir = System.getProperty("snapshotDir");
        if (dir == null || dir.isBlank()) return;
        try {
            Path out = Paths.get(dir.trim());
            java.nio.file.Files.createDirectories(out);
            WritableImage img = stage.getScene().snapshot(null);
            Path file = out.resolve(name + ".png");
            ImageIO.write(toBufferedImage(img), "png", file.toFile());
            System.out.println("[snapshot] " + file.toAbsolutePath()
                    + " (" + (int) img.getWidth() + "x" + (int) img.getHeight() + ")");
        } catch (final IOException | RuntimeException ex) {
            System.err.println("[snapshot] falhou " + name + ": " + ex);
        }
    }

    /**
     * Converte sem {@code javafx-swing}: aquele módulo existiria só para uma linha de
     * conveniência e engordaria a app-image distribuída. {@code java.desktop} já vem
     * no runtime, e a cópia pixel a pixel é barata para um snapshot ocasional.
     */
    private static BufferedImage toBufferedImage(final WritableImage img) {
        final var w = (int) img.getWidth();
        final var h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        return out;
    }

    /** Fechar a janela mata o capability host junto — filho não sobrevive ao pai. */
    @Override
    public void stop() {
        this.controlPlane.close();
    }

    public static void main(final String[] args) {
        launch(args);
    }
}
