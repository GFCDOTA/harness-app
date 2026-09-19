package harness.agent.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import harness.agent.domain.TraceRecorder;
import inspector.domain.TraceEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * ADAPTER — grava o trace do agente em JSONL, no MESMO diretório e no MESMO
 * envelope que o Inspector já lê.
 *
 * <p>É o que faz a execução de um comando aparecer na Pipeline View sem uma linha
 * de código novo de visualização: o agente não ganha um formato próprio, ele entra
 * no formato que existe.
 *
 * <p>Falha de escrita NÃO derruba o comando: perder o trace é ruim, perder a
 * operação por causa do trace é pior. O erro vai para stderr e a vida segue.
 */
public final class JsonlTraceRecorder implements TraceRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private BufferedWriter writer;
    private boolean broken;

    public JsonlTraceRecorder(final Path dir, final String runId) {
        this.file = dir.resolve(runId + ".jsonl");
        try {
            Files.createDirectories(dir);
            this.writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException ex) {
            System.err.println("[trace] não consegui abrir " + this.file + ": " + ex.getMessage());
            this.broken = true;
        }
    }

    public Path file() {
        return this.file;
    }

    @Override
    public void record(final TraceEvent event) {
        if (this.broken || this.writer == null) return;
        try {
            final var node = MAPPER.createObjectNode();
            node.put("v", 1);
            node.put("runId", event.runId());
            node.put("traceId", event.runId());
            node.put("spanId", event.spanId());
            if (event.parentSpanId() != null) node.put("parentSpanId", event.parentSpanId());
            node.put("seq", event.seq());
            node.put("ts", event.ts());
            if (event.durationMs() != null) node.put("durationMs", event.durationMs());
            node.put("component", event.component());
            node.put("category", event.category());
            node.put("status", event.status());
            node.put("name", event.name());
            node.set("meta", MAPPER.valueToTree(event.meta()));
            this.writer.write(MAPPER.writeValueAsString(node));
            this.writer.write("\n");
            this.writer.flush();
        } catch (final IOException | RuntimeException ex) {
            System.err.println("[trace] evento perdido: " + ex.getMessage());
            this.broken = true;
        }
    }

    @Override
    public void close() {
        if (this.writer == null) return;
        try {
            this.writer.close();
        } catch (final IOException ex) {
            System.err.println("[trace] falhou ao fechar: " + ex.getMessage());
        } finally {
            this.writer = null;
        }
    }
}
