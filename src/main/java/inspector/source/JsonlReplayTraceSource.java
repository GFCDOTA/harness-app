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
import java.util.stream.Stream;

/**
 * ADAPTER — lê um trace já gravado ({@code .ai_bridge/traces/<runId>.jsonl}).
 *
 * <p>É aqui que Jackson vive. O domínio não conhece JSON.
 */
public final class JsonlReplayTraceSource implements TraceSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};

    private final Path file;

    public JsonlReplayTraceSource(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    /** O .jsonl mais recente do diretório — conveniência para abrir "o último run". */
    public static JsonlReplayTraceSource newestIn(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            Path newest = s.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("nenhum .jsonl em " + dir));
            return new JsonlReplayTraceSource(newest);
        } catch (IOException e) {
            throw new UncheckedIOException("não consegui listar " + dir, e);
        }
    }

    @Override
    public String describe() {
        return "jsonl-replay: " + file.getFileName();
    }

    public Path file() {
        return file;
    }

    @Override
    public void stream(Consumer<TraceEvent> sink) {
        for (TraceEvent e : readAll()) {
            sink.accept(e);
        }
    }

    /** Lê tudo, ordenado por seq. Linha malformada NÃO derruba o replay — é reportada. */
    public List<TraceEvent> readAll() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("não consegui ler o trace " + file, e);
        }
        List<TraceEvent> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            try {
                out.add(toEvent(MAPPER.readValue(line, ROW)));
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "linha " + (i + 1) + " do trace é inválida: " + ex.getMessage(), ex);
            }
        }
        out.sort(Comparator.comparingLong(TraceEvent::seq));
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static TraceEvent toEvent(Map<String, Object> row) {
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

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long asLong(Object o) {
        if (o == null) throw new IllegalArgumentException("seq ausente no envelope");
        return ((Number) o).longValue();
    }

    private static Double asDouble(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }
}
