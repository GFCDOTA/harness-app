package inspector.source;

import inspector.domain.GptConsult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Le as consultas ao oraculo GPT gravadas em disco.
 *
 * <p>Convive com DOIS formatos sem privilegiar nenhum: o antigo
 * ({@code ## Question file} + {@code ## Raw response}, com a pergunta num arquivo
 * separado) e o novo ({@code ## Pergunta enviada} + {@code ## Resposta}). A
 * classificacao e por PALAVRA-CHAVE no titulo da secao, nao por posicao — formato
 * novo nao deve quebrar o leitor.
 */
public final class GptConsultLog {

    private static final Pattern SECTION = Pattern.compile("(?m)^##\\s+(.+)$");
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+)$");
    private static final Pattern STAMP = Pattern.compile("^(\\d{8}T\\d{6}Z)");
    private static final Pattern DATA_LINE = Pattern.compile("(?m)^-\\s+\\*\\*Data:\\*\\*\\s*(.+)$");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    private static final int EXCERPT = 4000;

    /**
     * Barra invertida por código, não por escape. O formato antigo grava caminho com
     * separador do Windows, e ele precisa virar '/' antes de virar Path.
     */
    private static final char BACKSLASH = (char) 92;

    private final Path dir;

    public GptConsultLog(final Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return this.dir;
    }

    /**
     * Mais recentes primeiro, por DATA DE ESCRITA do arquivo.
     *
     * <p>Nao ordena por nome: os registros novos comecam com letra
     * ({@code GPT_}, {@code SPIKE_}) e os antigos com digito, e em ordem descendente
     * as letras vencem os digitos — o que colocava um registro de ontem acima de um
     * de hoje. Nao ordena pela data declarada no documento tampouco: ela e opcional
     * e vem em formatos diferentes entre os dois layouts.
     *
     * <p>Diretorio ausente devolve lista vazia, nao explode.
     */
    public List<GptConsult> readAll() {
        if (!Files.isDirectory(this.dir)) return List.of();
        final List<Path> files;
        try (final var entries = Files.list(this.dir)) {
            files = entries.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparingLong(GptConsultLog::modifiedAt).reversed()
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (final IOException ex) {
            throw new UncheckedIOException("nao consegui listar " + this.dir, ex);
        }
        final var out = new ArrayList<GptConsult>(files.size());
        for (final var file : files) {
            try {
                out.add(parse(file));
            } catch (final IOException ex) {
                out.add(new GptConsult(file.getFileName().toString(), file.getFileName().toString(),
                        isoOf(file), "", 0L, "", "nao consegui ler: " + ex.getMessage()));
            }
        }
        return List.copyOf(out);
    }

    /** Epoch em ms; arquivo ilegivel vai para o fim em vez de derrubar a ordenacao. */
    private static long modifiedAt(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException ex) {
            return Long.MIN_VALUE;
        }
    }

    private static String isoOf(final Path path) {
        final var millis = modifiedAt(path);
        return millis == Long.MIN_VALUE ? "" : java.time.Instant.ofEpochMilli(millis).toString();
    }

    GptConsult parse(final Path file) throws IOException {
        final var text = Files.readString(file, StandardCharsets.UTF_8);
        final var name = file.getFileName().toString();
        final var sections = sections(text);

        var question = pick(sections, "pergunta", "question file", "question");
        var answer = pick(sections, "resposta", "raw response", "answer", "response");

        if (question.contains("`") && question.length() < 400) {
            final var resolved = follow(question, file);
            if (!resolved.isBlank()) question = resolved;
        }
        if (answer.isBlank()) {
            answer = text;
        }

        return new GptConsult(name, title(text, name), isoOf(file), when(text, name),
                Files.size(file), excerpt(question), excerpt(answer));
    }

    /**
     * Segue o ponteiro do formato antigo. Tenta o caminho absoluto do registro e, se
     * o repo mudou de lugar, procura o mesmo nome no diretorio irmao questions/.
     */
    private String follow(final String pointer, final Path responseFile) {
        final var matcher = BACKTICKED.matcher(pointer);
        if (!matcher.find()) return "";
        final var direct = Path.of(matcher.group(1).trim().replace(BACKSLASH, '/'));
        try {
            if (Files.isRegularFile(direct)) {
                return Files.readString(direct, StandardCharsets.UTF_8);
            }
            final var sibling = responseFile.getParent().resolveSibling("questions")
                    .resolve(direct.getFileName());
            if (Files.isRegularFile(sibling)) {
                return Files.readString(sibling, StandardCharsets.UTF_8);
            }
        } catch (final IOException | RuntimeException ignored) {
            // ponteiro quebrado nao e erro do painel; a metade que existe segue visivel
        }
        return "";
    }

    private static Map<String, String> sections(final String text) {
        final var out = new LinkedHashMap<String, String>();
        final var matcher = SECTION.matcher(text);
        final var marks = new ArrayList<int[]>();
        final var titles = new ArrayList<String>();
        while (matcher.find()) {
            marks.add(new int[]{matcher.start(), matcher.end()});
            titles.add(matcher.group(1).trim());
        }
        for (var i = 0; i < marks.size(); i++) {
            final var from = marks.get(i)[1];
            final var to = (i + 1 < marks.size()) ? marks.get(i + 1)[0] : text.length();
            out.put(titles.get(i).toLowerCase(), text.substring(from, to).trim());
        }
        return out;
    }

    private static String pick(final Map<String, String> sections, final String... keywords) {
        for (final var keyword : keywords) {
            for (final var entry : sections.entrySet()) {
                if (entry.getKey().contains(keyword)) return entry.getValue();
            }
        }
        return "";
    }

    private static String title(final String text, final String fallback) {
        final var matcher = H1.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    private static String when(final String text, final String name) {
        final var stamp = STAMP.matcher(name);
        if (stamp.find()) return stamp.group(1);
        final var data = DATA_LINE.matcher(text);
        if (data.find()) return data.group(1).trim();
        return "";
    }

    private static String excerpt(final String text) {
        final var trimmed = text.strip();
        return trimmed.length() <= EXCERPT ? trimmed : trimmed.substring(0, EXCERPT - 1) + "…";
    }
}
