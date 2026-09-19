package harness.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Configuração CENTRAL do Harness (missão §43).
 *
 * <p>A regra: URL, porta, caminho de máquina, nome de modelo e política de risco
 * NÃO ficam enterrados em componente de UI nem em handler. Ficam aqui, resolvidos
 * numa ordem só: propriedade de sistema {@code -Dchave} > variável de ambiente >
 * {@code harness.json} na raiz do app > convenção.
 *
 * <p>O MESMO {@code harness.json} é lido pelo capability host em Python
 * ({@code harness_caps/config.py}). Uma fonte, dois leitores — e por isso as chaves
 * têm o mesmo nome dos dois lados.
 */
public final class HarnessConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE_NAME = "harness.json";

    private final JsonNode file;
    private final Path appRoot;

    private HarnessConfig(final Path appRoot, final JsonNode file) {
        this.appRoot = appRoot;
        this.file = file;
    }

    /**
     * Resolve a raiz do Harness sem depender do diretório de trabalho.
     *
     * <p>Rodando por {@code mvnw javafx:run}, o working directory É o repo e {@code .}
     * resolve tudo. Rodando pelo ATALHO da área de trabalho, não: o app-image do
     * jpackage inicia noutro lugar, e {@code ./harness.json} simplesmente não existe
     * — o capability host não subiria e a tela diria "offline" sem explicar que o
     * motivo é o caminho, não o serviço.
     *
     * <p>Por isso o empacotado recebe {@code -DharnessHome=<repo>}; ver
     * {@code packaging/build-app.cmd}.
     */
    public static HarnessConfig load() {
        final var home = System.getProperty("harnessHome");
        if (home != null && !home.isBlank()) return load(Paths.get(home.trim()));
        final var env = System.getenv("HARNESS_HOME");
        if (env != null && !env.isBlank()) return load(Paths.get(env.trim()));
        return load(Paths.get("."));
    }

    public static HarnessConfig load(final Path appRoot) {
        final var path = appRoot.resolve(FILE_NAME);
        var node = (JsonNode) MAPPER.createObjectNode();
        if (Files.exists(path)) {
            try {
                node = MAPPER.readTree(Files.readString(path));
            } catch (final Exception ex) {  // NOSONAR — config ilegível cai na convenção
                System.err.println(
                        "[config] " + path + " ilegível, usando convenção: " + ex.getMessage());
            }
        }
        return new HarnessConfig(appRoot, node);
    }

    // -- resolução --------------------------------------------------------
    public String string(final String key, final String fallback) {
        final var property = System.getProperty(key);
        if (notBlank(property)) return property.trim();
        final var env = System.getenv(envName(key));
        if (notBlank(env)) return env.trim();
        final var node = this.file.path(key);
        if (node.isTextual() && notBlank(node.asText())) return node.asText().trim();
        return fallback;
    }

    public int integer(final String key, final int fallback) {
        final var raw = string(key, null);
        if (raw != null) {
            try {
                return Integer.parseInt(raw);
            } catch (final NumberFormatException ignored) {  // NOSONAR
                return fallback;
            }
        }
        final var node = this.file.path(key);
        return node.isNumber() ? node.asInt() : fallback;
    }

    public boolean flag(final String key, final boolean fallback) {
        final var raw = string(key, null);
        if (raw != null) return Boolean.parseBoolean(raw);
        final var node = this.file.path(key);
        return node.isBoolean() ? node.asBoolean() : fallback;
    }

    /** `pipelineRepo` -> `HARNESS_PIPELINE_REPO`. */
    private static String envName(final String key) {
        final var name = new StringBuilder("HARNESS");
        for (final var ch : key.toCharArray()) {
            if (Character.isUpperCase(ch)) name.append('_').append(ch);
            else if (ch == '.') name.append('_');
            else name.append(Character.toUpperCase(ch));
        }
        return name.toString();
    }

    private static boolean notBlank(final String value) {
        return value != null && !value.isBlank();
    }

    // -- o que o app pergunta ---------------------------------------------
    public Path appRoot() {
        return this.appRoot;
    }

    public Path pipelineRepo() {
        final var parent = this.appRoot.toAbsolutePath().getParent();
        return Paths.get(string("pipelineRepo", parent == null
                ? "../sketchup-mcp"
                : parent.resolve("sketchup-mcp").toString()));
    }

    public String project() {
        return string("project", "planta_74");
    }

    public String ollamaUrl() {
        return string("ollamaUrl", "http://127.0.0.1:11434");
    }

    public String agentModel() {
        return string("agentModel", "qwen2.5-coder:14b");
    }

    public Duration agentTimeout() {
        return Duration.ofSeconds(integer("agentTimeoutSec", 180));
    }

    public int maxAgentAttempts() {
        return integer("maxAgentAttempts", 4);
    }

    /** Política de risco. {@code false} = risco HIGH SEMPRE pede confirmação. */
    public boolean autoApproveHighRisk() {
        return flag("autoApproveHighRisk", false);
    }

    public Path traceDir() {
        return Paths.get(string("traceDir", this.appRoot.resolve("traces-local").toString()));
    }

    /**
     * O comando que sobe o capability host.
     *
     * <p>Declarado aqui e em mais lugar nenhum. O interpretador é o do venv do
     * pipeline porque é lá que moram shapely e o resto — o Python do sistema não
     * conseguiria importar os gates.
     */
    public List<String> capabilityHostCommand() {
        final var python = string("pythonExe",
                pipelineRepo().resolve(".venv/Scripts/python.exe").toString());
        return List.of(python, "-m", "harness_caps.host");
    }

    public Path capabilityHostDir() {
        return Paths.get(string("capabilitiesDir", this.appRoot.resolve("capabilities").toString()));
    }

    /** Ambiente do processo filho — o mesmo contrato de chaves do lado Python. */
    public Map<String, String> capabilityHostEnv() {
        return Map.of(
                "HARNESS_PIPELINE_REPO", pipelineRepo().toString(),
                "HARNESS_PROJECT", project(),
                "HARNESS_STATE_DIR", string("stateDir", this.appRoot.resolve("state-local").toString()),
                "PYTHONPATH", capabilityHostDir().toString(),
                "PYTHONIOENCODING", "utf-8",
                "PYTHONUNBUFFERED", "1");
    }
}
