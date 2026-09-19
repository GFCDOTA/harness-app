package harness.agent;

import harness.agent.domain.CapabilityHost;
import harness.agent.domain.Risk;
import harness.agent.domain.ToolCall;
import harness.agent.domain.ToolResult;
import harness.agent.domain.ToolSpec;
import harness.agent.domain.UnsupportedCapability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Dublê do capability host.
 *
 * <p>Existe para a suíte exercitar o laço INTEIRO sem SketchUp, sem Python e sem
 * Ollama (missão §29). Os schemas aqui são os mesmos que o host real publica — se
 * divergirem, o teste de contrato acusa.
 */
final class FakeCapabilityHost implements CapabilityHost {

    private final Map<String, Function<Map<String, Object>, ToolResult>> handlers = new LinkedHashMap<>();
    private final List<ToolSpec> specs = new ArrayList<>();
    final List<ToolCall> calls = new ArrayList<>();
    private boolean alive = true;

    static FakeCapabilityHost standard() {
        FakeCapabilityHost h = new FakeCapabilityHost();
        h.register(new ToolSpec("find_object", "acha objeto por termo",
                        Map.of("type", "object", "properties",
                                Map.of("query", Map.of("type", "string")),
                                "required", List.of("query")),
                        Map.of(), "NONE", true, Risk.LOW, false, false, List.of(), 30),
                args -> ToolResult.success("find_object",
                        Map.of("count", 1, "unique", "suite_01.escrivaninha",
                                "matches", List.of(Map.of("id", "suite_01.escrivaninha",
                                        "roomId", "r000"))), 4));

        h.register(new ToolSpec("move_object", "move objeto",
                        Map.of("type", "object", "properties",
                                Map.of("object_id", Map.of("type", "string"),
                                        "direction", Map.of("type", "string"),
                                        "distance_mm", Map.of("type", "number")),
                                "required", List.of("object_id", "direction", "distance_mm")),
                        Map.of(), "STATE_DELTA", true, Risk.LOW, true, true, List.of("scene"), 30),
                args -> {
                    final var direction = String.valueOf(args.get("direction"));
                    final var distance = ((Number) args.get("distance_mm")).doubleValue();
                    final var inches = distance / 25.4;
                    final var dx = "right".equals(direction) ? inches
                            : ("left".equals(direction) ? -inches : 0.0);
                    final var dy = "back".equals(direction) ? inches
                            : ("forward".equals(direction) ? -inches : 0.0);
                    return ToolResult.success("move_object",
                            Map.of("objectId", String.valueOf(args.get("object_id")),
                                    "roomId", "r000", "label", "Escrivaninha",
                                    "direction", direction,
                                    "distanceMm", args.get("distance_mm"),
                                    "partsMoved", 6,
                                    "bboxBefore", Map.of("x0", 100.0, "x1", 140.0,
                                            "y0", 200.0, "y1", 220.0, "z0", 0.0),
                                    "bboxAfter", Map.of("x0", 100.0 + dx, "x1", 140.0 + dx,
                                            "y0", 200.0 + dy, "y1", 220.0 + dy, "z0", 0.0)), 9);
                });

        h.register(new ToolSpec("run_gates", "roda os gates",
                        Map.of("type", "object", "properties",
                                Map.of("room_id", Map.of("type", "string")),
                                "required", List.of("room_id")),
                        Map.of(), "GATE", true, Risk.LOW, false, false, List.of("pipeline"), 180),
                args -> ToolResult.success("run_gates",
                        Map.of("roomId", args.get("room_id"), "overall", "PASS",
                                "clean", true, "findings", List.of()), 40));

        h.register(new ToolSpec("undo", "desfaz", Map.of("type", "object", "properties", Map.of()),
                        Map.of(), "NONE", true, Risk.LOW, true, true, List.of("scene"), 30),
                args -> ToolResult.success("undo", Map.of("undone", true, "remaining", 0), 3));

        h.register(new ToolSpec("delete_object", "apaga objeto",
                        Map.of("type", "object", "properties",
                                Map.of("object_id", Map.of("type", "string")),
                                "required", List.of("object_id")),
                        Map.of(), "NONE", true, Risk.HIGH, false, true, List.of("scene"), 30),
                args -> ToolResult.success("delete_object", Map.of("deleted", true), 5));
        return h;
    }

    void register(final ToolSpec spec, final Function<Map<String, Object>, ToolResult> handler) {
        specs.add(spec);
        handlers.put(spec.name(), handler);
    }

    void replace(final String tool, final Function<Map<String, Object>, ToolResult> handler) {
        handlers.put(tool, handler);
    }

    void kill() {
        alive = false;
    }

    @Override
    public List<ToolSpec> describeTools() {
        return List.copyOf(specs);
    }

    @Override
    public List<UnsupportedCapability> unsupported() {
        return List.of(new UnsupportedCapability("render", "slice 5"));
    }

    @Override
    public ToolResult invoke(final ToolCall call) {
        calls.add(call);
        var handler = handlers.get(call.tool());
        if (handler == null) {
            return ToolResult.failure(call.tool(), "UNKNOWN_TOOL", "não registrada", Map.of(), 1);
        }
        return handler.apply(call.args());
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    List<String> toolNamesCalled() {
        return calls.stream().map(ToolCall::tool).toList();
    }
}
