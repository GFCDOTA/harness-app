package harness.agent.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import harness.agent.domain.CapabilityHost;
import harness.agent.domain.Risk;
import harness.agent.domain.ToolCall;
import harness.agent.domain.ToolResult;
import harness.agent.domain.ToolSpec;
import harness.agent.domain.UnsupportedCapability;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ADAPTER — o capability host Python como PROCESSO FILHO, falando NDJSON.
 *
 * <p>Por que filho e não serviço: o pipeline é Python e vive noutro repo; subir um
 * servidor HTTP para ele significaria mais uma coisa viva na máquina, que ninguém
 * derruba e que um dia some sem explicação. Filho morre com o pai — a mesma regra
 * que vale para a janela do Inspector.
 *
 * <p>Por que não dar shell ao modelo (missão §20): o processo é lançado com um
 * comando DECLARADO na configuração, e a única coisa que trafega são chamadas de
 * tool com argumentos tipados. A página nunca monta uma linha de comando.
 *
 * <p>Não é thread-safe de propósito: um host, um interlocutor, respostas na ordem
 * dos pedidos. Quem chama é a thread de trabalho do agente.
 */
public final class StdioCapabilityHost implements CapabilityHost, AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> command;
    private final File workingDir;
    private final Map<String, String> env;
    private final long defaultTimeoutMs;
    private final AtomicLong ids = new AtomicLong();

    private Process process;
    private BufferedWriter toHost;
    private BufferedReader fromHost;
    private String startupError;
    private List<ToolSpec> tools = List.of();
    private List<UnsupportedCapability> unsupported = List.of();

    public StdioCapabilityHost(final List<String> command, final File workingDir,
                               final Map<String, String> env, final long defaultTimeoutMs) {
        this.command = List.copyOf(command);
        this.workingDir = workingDir;
        this.env = env == null ? Map.of() : Map.copyOf(env);
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * Sobe o processo e lê a tabela de capabilities.
     *
     * @return {@code true} se o host está atendendo. Falha NÃO lança: um capability
     *         host ausente é um estado que a tela mostra, não uma exceção que
     *         derruba a aplicação.
     */
    public boolean start() {
        try {
            final var builder = new ProcessBuilder(this.command);
            if (this.workingDir != null) builder.directory(this.workingDir);
            builder.environment().putAll(this.env);
            // stderr separado: stdout é canal de DADOS, e um traceback no meio do
            // NDJSON corromperia a resposta seguinte.
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            this.process = builder.start();
            this.toHost = new BufferedWriter(new OutputStreamWriter(
                    this.process.getOutputStream(), StandardCharsets.UTF_8));
            this.fromHost = new BufferedReader(new InputStreamReader(
                    this.process.getInputStream(), StandardCharsets.UTF_8));

            final var hello = readLine(this.defaultTimeoutMs);
            if (hello == null || !"ready".equals(hello.path("event").asText())) {
                this.startupError = "o host não anunciou prontidão";
                return false;
            }
            loadTools();
            return true;
        } catch (final IOException | RuntimeException ex) {
            this.startupError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return false;
        }
    }

    private void loadTools() {
        final var response = request("describe", null, null, this.defaultTimeoutMs);
        if (response == null || !response.path("ok").asBoolean()) {
            this.startupError = "describe falhou";
            return;
        }
        final var data = response.path("data");
        final var specs = new ArrayList<ToolSpec>();
        for (final var node : data.path("tools")) {
            specs.add(new ToolSpec(
                    node.path("name").asText(),
                    node.path("description").asText(),
                    MAPPER.convertValue(node.path("inputSchema"), Map.class),
                    MAPPER.convertValue(node.path("outputSchema"), Map.class),
                    node.path("verification").asText("NONE"),
                    node.path("implemented").asBoolean(true),
                    Risk.of(node.path("risk").asText()),
                    node.path("undoable").asBoolean(),
                    node.path("mutates").asBoolean(),
                    MAPPER.convertValue(node.path("requires"), List.class),
                    node.path("timeoutSec").asInt(60)));
        }
        final var missing = new ArrayList<UnsupportedCapability>();
        for (final var node : data.path("unsupported")) {
            missing.add(new UnsupportedCapability(node.path("name").asText(),
                    node.path("reason").asText()));
        }
        this.tools = List.copyOf(specs);
        this.unsupported = List.copyOf(missing);
    }

    @Override
    public List<ToolSpec> describeTools() {
        return this.tools;
    }

    @Override
    public List<UnsupportedCapability> unsupported() {
        return this.unsupported;
    }

    @Override
    public boolean isAlive() {
        return this.process != null && this.process.isAlive() && !this.tools.isEmpty();
    }

    public String startupError() {
        return this.startupError;
    }

    @Override
    public ToolResult invoke(final ToolCall call) {
        final var startedAt = System.nanoTime();
        final var timeout = this.tools.stream()
                .filter(spec -> spec.name().equals(call.tool()))
                .findFirst().map(spec -> spec.timeoutSec() * 1000L)
                .orElse(this.defaultTimeoutMs);

        final var response = request("invoke", call.tool(), call.args(), timeout);
        final var elapsed = (System.nanoTime() - startedAt) / 1_000_000L;
        if (response == null) {
            return ToolResult.failure(call.tool(), "HOST_SILENT",
                    "o capability host não respondeu em " + (timeout / 1000) + "s", Map.of(), elapsed);
        }
        if (response.path("ok").asBoolean()) {
            @SuppressWarnings("unchecked")
            final var data = (Map<String, Object>) MAPPER.convertValue(response.path("data"), Map.class);
            return ToolResult.success(call.tool(), data == null ? Map.of() : data, elapsed);
        }
        final var error = response.path("error");
        @SuppressWarnings("unchecked")
        final var extra = (Map<String, Object>) MAPPER.convertValue(error, Map.class);
        if (extra != null) {
            extra.remove("code");
            extra.remove("message");
        }
        return ToolResult.failure(call.tool(),
                error.path("code").asText("HOST_ERROR"),
                error.path("message").asText("sem detalhe"),
                extra == null ? Map.of() : extra, elapsed);
    }

    private synchronized JsonNode request(final String method, final String tool, final Map<String, Object> args,
                                          final long timeoutMs) {
        if (this.process == null || !this.process.isAlive()) return null;
        final var id = String.valueOf(this.ids.incrementAndGet());
        final var payload = new LinkedHashMap<String, Object>();
        payload.put("id", id);
        payload.put("method", method);
        if (tool != null) payload.put("tool", tool);
        if (args != null) payload.put("args", args);
        try {
            this.toHost.write(MAPPER.writeValueAsString(payload));
            this.toHost.write("\n");
            this.toHost.flush();
        } catch (final IOException ex) {
            return null;
        }
        // O host responde na ORDEM dos pedidos; ainda assim conferimos o id, para
        // uma resposta atrasada não ser lida como se fosse desta chamada.
        final var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            final var node = readLine(deadline - System.currentTimeMillis());
            if (node == null) return null;
            if (id.equals(node.path("id").asText())) return node;
        }
        return null;
    }

    private JsonNode readLine(final long timeoutMs) {
        final var deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        try {
            while (System.currentTimeMillis() < deadline) {
                if (this.fromHost.ready()) {
                    final var line = this.fromHost.readLine();
                    if (line == null) return null;
                    if (line.isBlank()) continue;
                    return MAPPER.readTree(line);
                }
                if (!this.process.isAlive()) return null;
                Thread.sleep(15);
            }
        } catch (final IOException ex) {
            return null;
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
        return null;
    }

    @Override
    public void close() {
        if (this.process == null) return;
        try {
            if (this.process.isAlive()) {
                this.toHost.write("{\"id\":\"bye\",\"method\":\"shutdown\"}\n");
                this.toHost.flush();
                this.process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (final IOException ex) {
            // encerramento best-effort: o destroy abaixo resolve
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            this.process.destroy();
        }
    }
}
