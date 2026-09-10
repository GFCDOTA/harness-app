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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    public GptConsultLog(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** Mais recentes primeiro. Diretorio ausente devolve lista vazia, nao explode. */
    public List<GptConsult> readAll() {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("nao consegui listar " + dir, e);
        }
        List<GptConsult> out = new ArrayList<>(files.size());
        for (Path f : files) {
            try {
                out.add(parse(f));
            } catch (IOException e) {
                out.add(new GptConsult(f.getFileName().toString(), f.getFileName().toString(),
                        "", 0L, "", "nao consegui ler: " + e.getMessage()));
            }
        }
        return List.copyOf(out);
    }

    GptConsult parse(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String name = file.getFileName().toString();
        Map<String, String> sections = sections(text);

        String question = pick(sections, "pergunta", "question file", "question");
        String answer = pick(sections, "resposta", "raw response", "answer", "response");

        if (question.contains("`") && question.length() < 400) {
            String resolved = follow(question, file);
            if (!resolved.isBlank()) question = resolved;
        }
        if (answer.isBlank()) {
            answer = text;
        }

        return new GptConsult(name, title(text, name), when(text, name),
                Files.size(file), excerpt(question), excerpt(answer));
    }

    /**
     * Segue o ponteiro do formato antigo. Tenta o caminho absoluto do registro e, se
     * o repo mudou de lugar, procura o mesmo nome no diretorio irmao questions/.
     */
    private String follow(String pointer, Path responseFile) {
        Matcher m = BACKTICKED.matcher(pointer);
        if (!m.find()) return "";
        Path direct = Path.of(m.group(1).trim().replace(BACKSLASH, '/'));
        try {
            if (Files.isRegularFile(direct)) {
                return Files.readString(direct, StandardCharsets.UTF_8);
            }
            Path sibling = responseFile.getParent().resolveSibling("questions")
                    .resolve(direct.getFileName());
            if (Files.isRegularFile(sibling)) {
                return Files.readString(sibling, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ignored) {
            // ponteiro quebrado nao e erro do painel; a metade que existe segue visivel
        }
        return "";
    }

    private static Map<String, String> sections(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = SECTION.matcher(text);
        List<int[]> marks = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            titles.add(m.group(1).trim());
        }
        for (int i = 0; i < marks.size(); i++) {
            int from = marks.get(i)[1];
            int to = (i + 1 < marks.size()) ? marks.get(i + 1)[0] : text.length();
            out.put(titles.get(i).toLowerCase(), text.substring(from, to).trim());
        }
        return out;
    }

    private static String pick(Map<String, String> sections, String... keywords) {
        for (String kw : keywords) {
            for (Map.Entry<String, String> e : sections.entrySet()) {
                if (e.getKey().contains(kw)) return e.getValue();
            }
        }
        return "";
    }

    private static String title(String text, String fallback) {
        Matcher m = H1.matcher(text);
        return m.find() ? m.group(1).trim() : fallback;
    }

    private static String when(String text, String name) {
        Matcher stamp = STAMP.matcher(name);
        if (stamp.find()) return stamp.group(1);
        Matcher data = DATA_LINE.matcher(text);
        if (data.find()) return data.group(1).trim();
        return "";
    }

    private static String excerpt(String s) {
        String t = s.strip();
        return t.length() <= EXCERPT ? t : t.substring(0, EXCERPT - 1) + "…";
    }
}
