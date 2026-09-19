package harness.agent;

import harness.agent.domain.LlmPlanner;
import harness.agent.domain.PlannerDecision;
import harness.agent.domain.ToolSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Planner de teste com roteiro fixo.
 *
 * <p>É assim que dá para testar o laço do agente de forma DETERMINÍSTICA: o que
 * está sob teste é a governança do Harness (validação, gate automático, teto de
 * tentativas, estado), não a criatividade do modelo. Texto de LLM em asserção seria
 * teste flaky — a regra de testing desta casa proíbe.
 */
final class ScriptedPlanner implements LlmPlanner {

    private final Deque<PlannerDecision> script = new ArrayDeque<>();
    private final List<AgentContext> seen = new ArrayList<>();
    private boolean available = true;
    private final String model;

    ScriptedPlanner(final String model, final PlannerDecision... decisions) {
        this.model = model;
        for (final PlannerDecision d : decisions) script.add(d);
    }

    void unavailable() {
        available = false;
    }

    @Override
    public PlannerDecision plan(final AgentContext context, final List<ToolSpec> tools, final List<Step> history) {
        seen.add(context);
        if (script.isEmpty()) {
            return PlannerDecision.finalAnswer("roteiro acabou");
        }
        return script.poll();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String modelName() {
        return model;
    }

    AgentContext lastContext() {
        return seen.get(seen.size() - 1);
    }

    List<AgentContext> contexts() {
        return List.copyOf(seen);
    }
}
