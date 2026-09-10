package inspector.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Descobre QUAL trace abrir. Puro e sem JavaFX de propósito: a regra de resolução
 * é testável sem tela.
 *
 * <p>Existe porque o caminho do trace deixou de ser vizinho: quando este app vive
 * em repositório próprio, {@code ../.ai_bridge/traces} não existe mais. A ordem é
 * explícita e, se nada resolver, ele <b>falha dizendo o que fazer</b> — abrir vazio
 * fingindo normalidade seria o mesmo pecado que o {@code rag.degraded} silencioso.
 */
public final class TraceLocator {

    public static final String PROP_FILE = "trace";
    public static final String PROP_DIR = "traceDir";
    public static final String ENV_DIR = "INSPECTOR_TRACE_DIR";

    private TraceLocator() {
    }

    /** Resolve usando as fontes do ambiente atual. */
    public static JsonlReplayTraceSource fromEnvironment(Path conventionalDir) {
        return new JsonlReplayTraceSource(resolve(
                System.getProperty(PROP_FILE),
                System.getProperty(PROP_DIR),
                System.getenv(ENV_DIR),
                conventionalDir));
    }

    /**
     * Precedência: arquivo explícito → diretório explícito → variável de ambiente →
     * diretório convencional (só se existir). Nenhuma delas: erro instrutivo.
     */
    public static Path resolve(String explicitFile, String explicitDir,
                               String envDir, Path conventionalDir) {
        if (isSet(explicitFile)) {
            Path f = Paths.get(explicitFile.trim());
            if (!Files.isRegularFile(f)) {
                throw new IllegalStateException("-D" + PROP_FILE + " aponta para algo que não é arquivo: " + f);
            }
            return f;
        }
        if (isSet(explicitDir)) {
            return newestIn(Paths.get(explicitDir.trim()), "-D" + PROP_DIR);
        }
        if (isSet(envDir)) {
            return newestIn(Paths.get(envDir.trim()), ENV_DIR);
        }
        if (conventionalDir != null && Files.isDirectory(conventionalDir)) {
            return newestIn(conventionalDir, "diretório convencional");
        }
        throw new IllegalStateException("""
                não sei qual trace abrir. Escolha uma:
                  -Dtrace=<arquivo.jsonl>        um trace específico
                  -DtraceDir=<diretório>         o .jsonl mais recente do diretório
                  INSPECTOR_TRACE_DIR=<diretório>  idem, por variável de ambiente\
                """);
    }

    private static Path newestIn(Path dir, String origem) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(origem + " não é um diretório: " + dir);
        }
        return JsonlReplayTraceSource.newestIn(dir).file();
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }
}
