package inspector.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import inspector.domain.TraceEvent;
import inspector.domain.TraceSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ADAPTER — lê um trace já gravado ({@code .ai_bridge/traces/<runId>.jsonl}).
 *
 * <p>É aqui que Jackson vive. O domínio não conhece JSON.
 */
public final class JsonlReplayTraceSource implements TraceSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};

    private final Path file;

    public JsonlReplayTraceSource(final Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    /** O .jsonl mais recente do diretório — conveniência para abrir "o último run". */
    public static JsonlReplayTraceSource newestIn(final Path dir) {
        try (final var entries = Files.list(dir)) {
            final var newest = entries
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("nenhum .jsonl em " + dir));
            return new JsonlReplayTraceSource(newest);
        } catch (final IOException ex) {
            throw new UncheckedIOException("não consegui listar " + dir, ex);
        }
    }

    @Override
    public String describe() {
        return "jsonl-replay: " + this.file.getFileName();
    }

    public Path file() {
        return this.file;
    }

    @Override
    public void stream(final Consumer<TraceEvent> sink) {
        for (final var event : readAll()) {
            sink.accept(event);
        }
    }

    /** Lê tudo, ordenado por seq. Linha malformada NÃO derruba o replay — é reportada. */
    public List<TraceEvent> readAll() {
        final List<String> lines;
        try {
            lines = Files.readAllLines(this.file, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            throw new UncheckedIOException("não consegui ler o trace " + this.file, ex);
        }
        final var out = new ArrayList<TraceEvent>(lines.size());
        for (var i = 0; i < lines.size(); i++) {
            final var line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            try {
                out.add(toEvent(MAPPER.readValue(line, ROW)));
            } catch (final Exception ex) {
                throw new IllegalStateException(
                        "linha " + (i + 1) + " do trace é inválida: " + ex.getMessage(), ex);
            }
        }
        out.sort(Comparator.comparingLong(TraceEvent::seq));
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static TraceEvent toEvent(final Map<String, Object> row) {
        return new TraceEvent(
                asLong(row.get("seq")),
                asString(row.get("runId")),
                asString(row.get("spanId")),
                asString(row.get("parentSpanId")),
                asString(row.get("ts")),
                asDouble(row.get("durationMs")),
                asString(row.get("component")),
                asString(row.get("category")),
                asString(row.get("status")),
                asString(row.get("name")),
                (Map<String, Object>) row.get("meta"));
    }

    private static String asString(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(final Object value) {
        if (value == null) throw new IllegalArgumentException("seq ausente no envelope");
        return ((Number) value).longValue();
    }

    private static Double asDouble(final Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
