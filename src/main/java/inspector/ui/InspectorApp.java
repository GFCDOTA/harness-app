package inspector.ui;

import inspector.domain.Run;
import inspector.domain.TraceEvent;
import inspector.projection.TraceProjection;
import inspector.source.JsonlReplayTraceSource;
import inspector.source.TraceLocator;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

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

    private final TraceProjection projection = new TraceProjection();

    @Override
    public void start(Stage stage) {
        boolean selfTest = Boolean.getBoolean("selftest");
        JsonlReplayTraceSource source = resolveSource();

        WebView view = new WebView();
        WebEngine engine = view.getEngine();
        WebBridge bridge = new WebBridge(engine);

        engine.setOnError(ev -> System.err.println("[inspector] webview: " + ev.getMessage()));

        stage.setTitle("AI Pipeline Inspector — " + source.describe());
        stage.setScene(new Scene(new BorderPane(view), 900, 760));
        stage.show();

        URL page = InspectorApp.class.getResource("/web/index.html");
        if (page == null) {
            throw new IllegalStateException("recurso /web/index.html não empacotado");
        }

        engine.getLoadWorker().stateProperty().addListener((obs, old, now) -> {
            if (now == Worker.State.SUCCEEDED) {
                whenReady(bridge, 0, () -> {
                    bridge.loadRun(projection.toJson(read(source), source.describe()));
                    if (selfTest) selfTest(bridge, stage);
                });
            } else if (now == Worker.State.FAILED) {
                System.err.println("[inspector] falhou carregar a UI");
                stage.close();
            }
        });
        engine.load(page.toExternalForm());
    }

    /** O domínio é montado a partir do port, nunca do arquivo direto. */
    private Run read(JsonlReplayTraceSource source) {
        List<TraceEvent> collected = new ArrayList<>();
        source.stream(collected::add);
        return Run.fromEvents(collected);
    }

    /** A regra de resolução vive no TraceLocator, que é testável sem tela. */
    private JsonlReplayTraceSource resolveSource() {
        return TraceLocator.fromEnvironment(CONVENTIONAL_TRACE_DIR);
    }

    /** A UI registra a API de forma assíncrona; espera o flag em vez de chutar um sleep. */
    private void whenReady(WebBridge bridge, int attempt, Runnable then) {
        boolean ready;
        try {
            ready = bridge.isReady();
        } catch (RuntimeException ex) {
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
    private void selfTest(WebBridge bridge, Stage stage) {
        step(700, () -> System.out.println("[selftest] pipeline " + bridge.probe()),
        () -> step(400, () -> bridge.exec("document.querySelector('.react-flow__node-step')"
                        + ".dispatchEvent(new MouseEvent('click',{bubbles:true}))"),
        () -> step(400, () -> System.out.println("[selftest] detalhe  " + bridge.probe()),
        () -> step(300, () -> bridge.exec("window.inspector.setView('events')"),
        () -> step(400, () -> {
            System.out.println("[selftest] events   " + bridge.probe());
            stage.close();
        }, null)))));
    }

    /** Encadeia passos do smoke check sem aninhar Timeline na mao em cada ponto. */
    private void step(int delayMs, Runnable action, Runnable next) {
        Timeline t = new Timeline(new KeyFrame(Duration.millis(delayMs), e -> {
            action.run();
            if (next != null) next.run();
        }));
        t.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
