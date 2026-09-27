package harness.agent.domain;

import java.util.Set;

/**
 * A taxonomia FECHADA de eventos do CONTROL PLANE. Declarada, não emergente.
 *
 * <p>Por que existe, e por que virou código e não comentário:
 *
 * <p>O pipeline (`sketchup-mcp/core/observability`) tem um catálogo fechado de 44
 * nomes, validado lá. O plano do agente sempre emitiu nomes de FORA desse
 * catálogo — `agent.plan`, `capability.lookup`, `tool.invoke`, `tool.verified`,
 * `gate.run` não estão nos 44; só `run.started` e `run.finished` estão. Isso vinha
 * do slice 1 e nunca foi declarado em lugar nenhum.
 *
 * <p>Quando o gate de alvo acrescentou `target.guard.*`, o raciocínio usado foi
 * "não há validator no Java, então nome novo é seguro". Isso está errado: a
 * AUSÊNCIA de mecanismo não é autorização. É exatamente assim que um catálogo dito
 * fechado deixa de ser fechado sem ninguém perceber. Apontado em review, e a
 * correção é esta classe.
 *
 * <p><b>São duas taxonomias, de propósito, e agora isso é explícito.</b> Um evento
 * do pipeline descreve uma RUN de geração; um evento do agente descreve um COMANDO
 * do Felipe. Acrescentar nome aqui é ato deliberado — {@code HarnessEventsTest}
 * trava o conjunto e quebra se alguém emitir algo não declarado.
 *
 * <p><b>Divergência conhecida, ainda não reconciliada:</b> o pipeline usa
 * `tool.started`/`tool.finished`/`tool.failed` e `gate.started`/`gate.passed`;
 * o agente usa `tool.invoke` (um evento com resultado) e `gate.run`. Unificar
 * mexeria na projeção do Inspector e é decisão própria — ver
 * {@code docs/adr/ADR-002-taxonomia-de-eventos.md}.
 */
public final class HarnessEvents {

    private HarnessEvents() {
    }

    /** Terminais do envelope: abrem e fecham o comando. Compartilhados com o pipeline. */
    public static final String RUN_STARTED = "run.started";
    public static final String RUN_FINISHED = "run.finished";

    /** O planejador local decidiu (ou falhou em decidir). */
    public static final String AGENT_PLAN = "agent.plan";

    /** Lookup no tool registry — ANTES de validar argumento (slice 1.5). */
    public static final String CAPABILITY_LOOKUP = "capability.lookup";

    /** Uma capability executou, com o resultado no mesmo evento. */
    public static final String TOOL_INVOKE = "tool.invoke";

    /** A governança barrou a chamada: risco, argumento inventado, trava, repetição. */
    public static final String TOOL_REJECTED = "tool.rejected";

    /** A prova de que o efeito aconteceu (STATE_DELTA / ARTIFACT / GATE). */
    public static final String TOOL_VERIFIED = "tool.verified";

    /** Gates determinísticos sobre o cômodo alterado. */
    public static final String GATE_RUN = "gate.run";

    /** Procedência da resolução de alvo — o gate autorizou. */
    public static final String TARGET_GUARD_ALLOWED = "target.guard.allowed";

    /** Procedência da resolução de alvo — o gate bloqueou. */
    public static final String TARGET_GUARD_BLOCKED = "target.guard.blocked";

    /**
     * O conjunto fechado. Versionado junto com o envelope v1.
     *
     * <p>Acrescentar aqui é declarar. Emitir sem declarar é erro em tempo de
     * execução, não silêncio.
     */
    public static final Set<String> CLOSED = Set.of(
            RUN_STARTED, RUN_FINISHED, AGENT_PLAN, CAPABILITY_LOOKUP,
            TOOL_INVOKE, TOOL_REJECTED, TOOL_VERIFIED, GATE_RUN,
            TARGET_GUARD_ALLOWED, TARGET_GUARD_BLOCKED);

    /** Falha ALTO num nome não declarado. Drift silencioso é o que isto impede. */
    public static String require(final String name) {
        if (!CLOSED.contains(name)) {
            throw new IllegalArgumentException(
                    "evento '" + name + "' não está na taxonomia declarada do Harness. "
                            + "Declare em HarnessEvents.CLOSED antes de emitir — "
                            + "catálogo fechado que cresce sozinho não é fechado. "
                            + "Declarados: " + CLOSED.stream().sorted().toList());
        }
        return name;
    }
}
