package inspector.ui;

import inspector.domain.Run;
import inspector.domain.TraceEvent;
import inspector.projection.TraceProjection;
import inspector.source.JsonlReplayTraceSource;
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

    private static final Path DEFAULT_TRACE_DIR = Paths.get("..", ".ai_bridge", "traces");

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

        URL page = InspectorApp.class.getResource("/web/inspector.html");
        if (page == null) {
            throw new IllegalStateException("recurso /web/inspector.html não empacotado");
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

    private JsonlReplayTraceSource resolveSource() {
        String override = System.getProperty("trace");
        if (override != null && !override.isBlank()) {
            return new JsonlReplayTraceSource(Paths.get(override));
        }
        return JsonlReplayTraceSource.newestIn(DEFAULT_TRACE_DIR);
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

    private void selfTest(WebBridge bridge, Stage stage) {
        new Timeline(new KeyFrame(Duration.millis(700), e -> {
            System.out.println("[selftest] " + bridge.probe());
            stage.close();
        })).play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
