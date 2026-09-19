package harness.agent.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import harness.agent.domain.AgentState;
import harness.agent.domain.LlmPlanner;
import harness.agent.domain.PlannerDecision;
import harness.agent.domain.ToolCall;
import harness.agent.domain.ToolSpec;
import harness.agent.domain.UnsupportedCapability;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADAPTER — o modelo local no Ollama, via {@code /api/chat} com tool calling nativo.
 *
 * <p>O modelo é CONFIGURÁVEL (missão §7): nenhuma regra do Harness depende de um
 * model id. Trocar de modelo é trocar uma string de configuração.
 *
 * <p>O prompt é curto de propósito. O que vai para o modelo é um RETRATO do estado
 * (projeto, cômodo ativo, último objeto, travas) mais os schemas das tools — não o
 * projeto inteiro nem a conversa completa (missão §41). Com 404 peças na cena,
 * despejar a cena no prompt seria caro e pior: o modelo passaria a inventar ids em
 * vez de chamar {@code find_object}.
 */
public final class OllamaPlanner implements LlmPlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI chatUri;
    private final URI tagsUri;
    private final String model;
    private final HttpClient http;
    private final Duration timeout;

    /** O que o sistema ainda NAO faz. Vai para o prompt; ver knowsUnsupported. */
    private List<UnsupportedCapability> unsupported = List.of();

    public OllamaPlanner(final String baseUrl, final String model, final Duration timeout) {
        final var base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chatUri = URI.create(base + "/api/chat");
        this.tagsUri = URI.create(base + "/api/tags");
        this.model = model;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String modelName() {
        return this.model;
    }

    @Override
    public void knowsUnsupported(final List<UnsupportedCapability> unsupported) {
        this.unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
    }

    @Override
    public boolean isAvailable() {
        try {
            final var request = HttpRequest.newBuilder(this.tagsUri)
                    .timeout(Duration.ofSeconds(3)).GET().build();
            final var response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            final var models = MAPPER.readTree(response.body()).path("models");
            for (final var node : models) {
                if (this.model.equals(node.path("name").asText())) return true;
            }
            // Ollama no ar mas sem ESTE modelo: dizer que está disponível seria
            // mentir, e a falha apareceria só no meio do comando.
            return false;
        } catch (final Exception ex) {  // NOSONAR — indisponível é resposta, não exceção
            return false;
        }
    }

    @Override
    public PlannerDecision plan(final AgentContext context, final List<ToolSpec> tools, final List<Step> history) {
        final var body = MAPPER.createObjectNode();
        body.put("model", this.model);
        body.put("stream", false);
        body.set("messages", messages(context, history));
        body.set("tools", toolSchemas(tools));
        final var options = body.putObject("options");
        // temperatura baixa: aqui o modelo escolhe uma tool, não escreve prosa.
        options.put("temperature", 0.1);

        try {
            final var request = HttpRequest.newBuilder(this.chatUri)
                    .timeout(this.timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            final var response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return PlannerDecision.unavailable(
                        "Ollama respondeu HTTP " + response.statusCode() + ": " + cap(response.body()));
            }
            return parse(MAPPER.readTree(response.body()));
        } catch (final HttpTimeoutException ex) {
            return PlannerDecision.unavailable(
                    "o modelo passou de " + this.timeout.toSeconds() + "s sem responder");
        } catch (final Exception ex) {  // NOSONAR
            return PlannerDecision.unavailable(
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /** Traduz a resposta do Ollama para uma decisão. Resposta ilegível NÃO vira ação. */
    PlannerDecision parse(final JsonNode root) {
        final var message = root.path("message");
        if (message.isMissingNode()) {
            return PlannerDecision.unavailable("resposta sem campo 'message'");
        }
        final var toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            final var calls = new ArrayList<ToolCall>();
            for (final var node : toolCalls) {
                final var function = node.path("function");
                final var name = function.path("name").asText();
                if (name.isBlank()) continue;
                calls.add(new ToolCall(name, arguments(function.path("arguments"))));
            }
            if (!calls.isEmpty()) return PlannerDecision.callTools(calls);
        }
        final var content = message.path("content").asText("").strip();
        if (content.isBlank()) {
            return PlannerDecision.unavailable("o modelo não pediu tool nem respondeu texto");
        }
        // Modelos locais nem sempre usam o canal `tool_calls` do Ollama: o
        // qwen2.5-coder despeja {"name":…,"arguments":{…}} dentro de `content`.
        // Sem tratar isso, a chamada virava "resposta final" e o comando terminava
        // ANSWERED com um blob de JSON como resumo — dizendo que atendeu sem ter
        // executado nada. O nome ainda passa pelo registry; aqui só se reconhece a
        // FORMA, não se confia nela.
        final var embedded = toolCallsInText(content);
        if (!embedded.isEmpty()) return PlannerDecision.callTools(embedded);
        return PlannerDecision.finalAnswer(content);
    }

    /**
     * Procura chamadas de tool escritas como texto, inclusive dentro de cerca
     * markdown. Devolve vazio quando o texto é só texto — prosa não vira ação.
     */
    private List<ToolCall> toolCallsInText(final String content) {
        var body = content;
        if (body.startsWith("```")) {
            final var firstBreak = body.indexOf('\n');
            final var lastFence = body.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                body = body.substring(firstBreak + 1, lastFence).strip();
            }
        }
        if (!(body.startsWith("{") || body.startsWith("["))) return List.of();
        final JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception ex) {  // NOSONAR — texto que não é JSON é só texto
            return List.of();
        }
        final var calls = new ArrayList<ToolCall>();
        for (final var candidate : node.isArray() ? node : MAPPER.createArrayNode().add(node)) {
            // aceita {"name":…} e também {"function":{"name":…}}
            final var fn = candidate.has("function") ? candidate.path("function") : candidate;
            final var name = fn.path("name").asText("");
            if (name.isBlank()) continue;
            calls.add(new ToolCall(name, arguments(
                    fn.has("arguments") ? fn.path("arguments") : fn.path("parameters"))));
        }
        return calls;
    }

    /**
     * Argumentos do modelo, tolerando os dois formatos que modelos locais produzem.
     *
     * <p>Ilegível devolve VAZIO, nunca um palpite: o registry recusa, o modelo vê o
     * erro e corrige. Inventar um argumento aqui seria o Harness alucinando no lugar
     * do modelo.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> arguments(final JsonNode raw) {
        if (raw.isObject()) {
            return MAPPER.convertValue(raw, Map.class);
        }
        if (raw.isTextual()) {
            try {
                final var parsed = MAPPER.readTree(raw.asText());
                if (parsed.isObject()) return MAPPER.convertValue(parsed, Map.class);
            } catch (final Exception ex) {  // NOSONAR — argumento ilegível vira vazio
                return Map.of();
            }
        }
        return Map.of();
    }

    private ArrayNode messages(final AgentContext context, final List<Step> history) {
        final var messages = MAPPER.createArrayNode();
        messages.add(MAPPER.createObjectNode().put("role", "system")
                .put("content", systemPrompt()));
        messages.add(MAPPER.createObjectNode().put("role", "user")
                .put("content", statePrompt(context) + "\n\nCOMANDO: " + context.command()));

        for (final var step : history) {
            final var assistant = MAPPER.createObjectNode().put("role", "assistant").put("content", "");
            final var calls = assistant.putArray("tool_calls");
            final var function = calls.addObject().putObject("function");
            function.put("name", step.call().tool());
            function.set("arguments", MAPPER.valueToTree(step.call().args()));
            messages.add(assistant);
            messages.add(MAPPER.createObjectNode().put("role", "tool")
                    .put("content", resultForModel(step)));
        }
        return messages;
    }

    /**
     * O que o modelo vê de um resultado.
     *
     * <p>Resumido de propósito: devolver o JSON inteiro de {@code list_objects} com
     * 64 objetos estoura o contexto de um modelo local e faz ele perder o comando.
     * O que ele precisa é do suficiente para o próximo passo.
     */
    private String resultForModel(final Step step) {
        try {
            if (!step.result().ok()) {
                final var error = new LinkedHashMap<String, Object>();
                error.put("ok", false);
                error.put("error", step.result().errorCode());
                error.put("message", step.result().errorMessage());
                if (step.result().errorExtra().containsKey("candidates")) {
                    error.put("candidates", step.result().errorExtra().get("candidates"));
                }
                return MAPPER.writeValueAsString(error);
            }
            final var data = new LinkedHashMap<String, Object>(step.result().data());
            // COMPACTAR, não truncar. Cortar a lista nos 12 primeiros fez o modelo
            // responder "não encontrei nenhuma cama nos quartos" — em ordem
            // alfabética os 12 primeiros são todos da área de serviço, e as camas
            // nunca chegavam a ele. Dado incompleto produz conclusão errada com
            // toda a confiança; lista inteira em forma curta não.
            compact(data, "objects");
            compact(data, "matches");
            trim(data, "edits", 5);
            data.remove("baseline");
            final var json = MAPPER.writeValueAsString(Map.of("ok", true, "data", data));
            return json.length() > 4000 ? json.substring(0, 4000) + "…" : json;
        } catch (final Exception ex) {  // NOSONAR
            return "{\"ok\":false,\"error\":\"resultado não serializável\"}";
        }
    }

    /**
     * Reduz cada objeto ao que o modelo precisa para escolher: id, cômodo e trava.
     *
     * <p>bbox, peças e kinds são geometria — quem usa isso são os gates, não o
     * planner. Tirando-os, os 64 objetos da planta cabem no contexto inteiros.
     */
    private static void compact(final Map<String, Object> data, final String key) {
        if (!(data.get(key) instanceof List<?> list)) return;
        final var compacted = new ArrayList<Object>(list.size());
        for (final var entry : list) {
            if (entry instanceof Map<?, ?> row) {
                final var slim = new LinkedHashMap<String, Object>();
                for (final var field : List.of("id", "room", "roomId", "locked")) {
                    if (row.get(field) != null) slim.put(field, row.get(field));
                }
                compacted.add(slim.isEmpty() ? row : slim);
            } else {
                compacted.add(entry);
            }
        }
        data.put(key, compacted);
    }

    private static void trim(final Map<String, Object> data, final String key, final int max) {
        if (data.get(key) instanceof List<?> list && list.size() > max) {
            data.put(key, new ArrayList<Object>(list.subList(0, max)));
            data.put(key + "Truncated", list.size() - max);
        }
    }

    private String systemPrompt() {
        final var prompt = new StringBuilder("""
                Você é o planejador do Harness, que opera a planta de um apartamento no SketchUp.

                COMO VOCÊ TRABALHA
                - Você NÃO executa nada e NÃO toca no sistema. Você escolhe uma tool e preenche \
                os argumentos; o Harness executa e os gates determinísticos validam.
                - NUNCA invente id de objeto. Se o usuário falar "a mesa", "a escrivaninha", \
                "o sofá", chame find_object primeiro e use o id que voltar.
                - NUNCA calcule eixo nem coordenada. Para mover, use direction (left/right/\
                forward/back) e distance_mm; o Harness resolve a geometria.
                - Se uma tool devolver erro AMBIGUOUS, NÃO escolha por conta própria: responda \
                em texto listando as opções e peça para o usuário escolher.
                - Se o comando se referir a algo anterior ("move mais 10 cm", "desfaz", "gira \
                mais"), use o ESTADO ATUAL que vem no pedido: ele diz qual foi o último objeto.
                - Não chame run_gates depois de mover: o Harness roda os gates sozinho.
                - Quando o objetivo estiver cumprido, responda em TEXTO (sem tool) com um resumo \
                de uma ou duas linhas, em português, do que foi feito.

                PEDIDO VAGO NÃO VIRA ALTERAÇÃO
                - Se o comando não diz O QUE mudar, NÃO escolha por ele. "altere a cama", \
                "melhora isso", "ajeita o quarto" não são instruções: são conversas. Responda em \
                TEXTO perguntando o que ele quer mudar, e ofereça opções concretas.
                - NUNCA invente uma distância, uma direção ou um ângulo que o usuário não disse. \
                Mover 10 cm "para ver" é alterar o projeto dele sem permissão. O Harness recusa \
                essas chamadas de qualquer forma; perguntar é mais rápido.
                - Comando no plural ("a cama dos quartos") afeta MAIS DE UM objeto. Liste o que \
                encontrou e pergunte, em vez de escolher um.
                - NUNCA invente um room_id. Para achar um móvel pelo NOME use find_object, que \
                varre a planta inteira. Só passe room_id se o usuário nomeou o cômodo ou se o id \
                veio de list_rooms. Filtrar por um cômodo que você chutou faz você concluir que \
                algo "não existe" quando ele está no cômodo ao lado.

                O QUE VOCÊ NÃO CONSEGUE FAZER
                Se o pedido for uma destas, responda em TEXTO dizendo QUAL capability falta e \
                o motivo que está na lista abaixo — não só "não faço isso". E NÃO tente \
                contornar com outra tool: usar find_object para procurar algo que não existe \
                é uma resposta errada.
                """);

        if (!this.unsupported.isEmpty()) {
            for (final var missing : this.unsupported) {
                prompt.append("- ").append(missing.name()).append(": ")
                        .append(missing.reason()).append('\n');
            }
        }
        prompt.append("\nUma tool por vez. Português nas respostas.\n");
        return prompt.toString();
    }

    private String statePrompt(final AgentContext context) {
        final var prompt = new StringBuilder("ESTADO ATUAL\n");
        prompt.append("- projeto: ").append(context.project()).append('\n');
        prompt.append("- cômodo ativo: ").append(or(context.activeRoom(), "nenhum")).append('\n');
        prompt.append("- último objeto referenciado: ")
                .append(or(context.lastReferencedObject(), "nenhum")).append('\n');
        prompt.append("- última ação: ").append(or(context.lastAction(), "nenhuma")).append('\n');
        prompt.append("- alterações desfazíveis: ").append(context.pendingEdits()).append('\n');
        if (!context.lockedObjects().isEmpty()) {
            prompt.append("- TRAVADOS (não pode mover): ")
                    .append(String.join(", ", context.lockedObjects())).append('\n');
        }
        if (!context.recentTurns().isEmpty()) {
            prompt.append("\nCOMANDOS RECENTES\n");
            for (final AgentState.Turn turn : context.recentTurns()) {
                prompt.append("- \"").append(turn.command()).append("\" -> ")
                        .append(turn.status()).append('\n');
            }
        }
        return prompt.toString();
    }

    private ArrayNode toolSchemas(final List<ToolSpec> tools) {
        final var schemas = MAPPER.createArrayNode();
        for (final var spec : tools) {
            final var tool = schemas.addObject();
            tool.put("type", "function");
            final var function = tool.putObject("function");
            function.put("name", spec.name());
            function.put("description", spec.description());
            function.set("parameters", MAPPER.valueToTree(spec.inputSchema()));
        }
        return schemas;
    }

    private static String or(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String cap(final String text) {
        if (text == null) return "";
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
