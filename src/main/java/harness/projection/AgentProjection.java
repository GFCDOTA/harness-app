package harness.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import harness.agent.domain.AgentOutcome;
import harness.agent.domain.AgentState;
import harness.agent.domain.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FRONTEIRA do painel do agente: domínio vira JSON, a UI só consome.
 *
 * <p>Mesma direção única do trace e do oráculo. O React não conhece
 * {@link AgentOutcome} nem {@link AgentState}; conhece um payload.
 */
public final class AgentProjection {

    private final ObjectMapper mapper = new ObjectMapper();

    public String toJson(final Map<String, Object> payload) {
        try {
            return this.mapper.writeValueAsString(payload);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("nao consegui serializar o painel do agente", ex);
        }
    }

    /** Retrato completo: estado, capabilities e o desfecho do último comando. */
    public Map<String, Object> snapshot(final AgentState state, final ToolRegistry registry,
                                        final AgentOutcome last, final String model,
                                        final boolean modelUp, final boolean hostUp,
                                        final String hostDetail) {
        final var out = new LinkedHashMap<String, Object>();
        out.put("project", state.activeProject());
        out.put("activeRoom", state.activeRoom().orElse(null));
        out.put("lastReferencedObject", state.lastReferencedObject().orElse(null));
        out.put("lockedObjects", List.copyOf(state.lockedObjects()));
        out.put("pendingEdits", state.pendingEdits());
        out.put("undoAvailable", state.undoAvailable());
        out.put("model", model);
        out.put("modelUp", modelUp);
        out.put("hostUp", hostUp);
        out.put("hostDetail", hostDetail);
        out.put("tools", tools(registry));
        out.put("unsupported", unsupported(registry));
        out.put("turns", turns(state));
        out.put("last", last == null ? null : outcome(last));
        return out;
    }

    private List<Map<String, Object>> tools(final ToolRegistry registry) {
        final var out = new ArrayList<Map<String, Object>>();
        if (registry == null) return out;
        for (final var spec : registry.all()) {
            final var row = new LinkedHashMap<String, Object>();
            row.put("name", spec.name());
            row.put("description", spec.description());
            row.put("risk", spec.risk().name());
            row.put("undoable", spec.undoable());
            row.put("mutates", spec.mutates());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> unsupported(final ToolRegistry registry) {
        final var out = new ArrayList<Map<String, Object>>();
        if (registry == null) return out;
        for (final var missing : registry.unsupported()) {
            final var row = new LinkedHashMap<String, Object>();
            row.put("name", missing.name());
            row.put("reason", missing.reason());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> turns(final AgentState state) {
        final var out = new ArrayList<Map<String, Object>>();
        for (final var turn : state.recentTurns(20)) {
            final var row = new LinkedHashMap<String, Object>();
            row.put("command", turn.command());
            row.put("status", turn.status());
            row.put("summary", turn.summary());
            out.add(row);
        }
        return out;
    }

    /** O desfecho como a UI precisa ver: o que mudou, o que o gate disse, e o trace. */
    public Map<String, Object> outcome(final AgentOutcome outcome) {
        final var out = new LinkedHashMap<String, Object>();
        out.put("status", outcome.status().name());
        out.put("summary", outcome.summary());
        out.put("traceId", outcome.traceId());
        out.put("undoAvailable", outcome.undoAvailable());
        out.put("changedSystem", outcome.changedSystem());
        out.put("options", outcome.options());

        final var actions = new ArrayList<Map<String, Object>>();
        for (final var action : outcome.actions()) {
            final var row = new LinkedHashMap<String, Object>();
            row.put("tool", action.tool());
            row.put("args", action.args());
            row.put("ok", action.ok());
            row.put("mutating", action.mutating());
            row.put("detail", action.detail());
            actions.add(row);
        }
        out.put("actions", actions);
        out.put("gateResults", outcome.gateResults());
        return out;
    }
}
