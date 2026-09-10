package inspector.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.scene.web.WebEngine;

/**
 * A fronteira Java → JavaScript, estreita e explícita.
 *
 * <p>DELIBERADAMENTE sem {@code netscape.javascript.JSObject}: o spike de 2026-09-09
 * mostrou que ele está deprecated e marcado para remoção. Aqui o Java só chama uma
 * API JS mínima passando <b>uma string JSON</b>; nenhum objeto Java é exposto ao
 * JavaScript, e a superfície total da bridge são os dois métodos abaixo.
 *
 * <p>O JSON é re-serializado como literal de string JS ({@code mapper.writeValueAsString}
 * de uma String faz exatamente isso), então quote, barra invertida e acento não
 * conseguem escapar do argumento e virar código.
 */
public final class WebBridge {

    private static final String API = "window.inspector";

    private final WebEngine engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebBridge(WebEngine engine) {
        this.engine = engine;
    }

    /** {@code window.inspector.loadRun(json)} — troca a run inteira. */
    public void loadRun(String runJson) {
        call("loadRun", runJson);
    }

    /** {@code window.inspector.appendEvent(json)} — usado quando o SSE entrar. */
    public void appendEvent(String eventJson) {
        call("appendEvent", eventJson);
    }

    /** {@code true} se a UI já registrou a API. */
    public boolean isReady() {
        Object r = engine.executeScript(
                "!!(" + API + " && " + API + ".ready === true)");
        return Boolean.TRUE.equals(r);
    }

    public String probe() {
        return String.valueOf(engine.executeScript("JSON.stringify(" + API + ".probe())"));
    }

    private void call(String fn, String json) {
        engine.executeScript(API + "." + fn + "(" + asJsStringLiteral(json) + ")");
    }

    private String asJsStringLiteral(String raw) {
        try {
            return mapper.writeValueAsString(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("não consegui escapar o payload para o JS", e);
        }
    }
}
