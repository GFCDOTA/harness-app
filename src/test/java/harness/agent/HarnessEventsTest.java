package harness.agent;

import harness.agent.domain.AgentTrace;
import harness.agent.domain.HarnessEvents;
import harness.agent.domain.TraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trava a taxonomia de eventos do control plane.
 *
 * <p>Existe por causa de um erro de raciocínio meu, apontado em review: eu
 * verifiquei que o Java não valida nome de evento e concluí que acrescentar
 * {@code target.*} era seguro. A ausência de mecanismo não é autorização — é
 * assim que um catálogo dito FECHADO deixa de ser fechado sem ninguém perceber.
 *
 * <p>O teste tem duas funções: falhar quando alguém emitir nome não declarado, e
 * falhar quando alguém acrescentar nome ao conjunto sem tocar nesta lista. As
 * duas direções importam — a segunda é o que transforma "declarar" num ato
 * consciente em vez de um efeito colateral.
 */
class HarnessEventsTest {

    @Test
    void oConjuntoFechadoEexatamenteEste() {
        // Mudar esta lista e mudar um CONTRATO. Se você está aqui porque o teste
        // quebrou: confirme que o nome novo é mesmo do plano do AGENTE (comando do
        // Felipe) e não do pipeline (run de geração), e registre no ADR-002.
        assertEquals(List.of(
                        "agent.plan",
                        "capability.lookup",
                        "gate.run",
                        "run.finished",
                        "run.started",
                        "target.guard.allowed",
                        "target.guard.blocked",
                        "tool.invoke",
                        "tool.rejected",
                        "tool.verified"),
                HarnessEvents.CLOSED.stream().sorted().toList());
    }

    @Test
    void emitirNomeNaoDeclaradoFALHAalto() {
        final var trace = new AgentTrace("run_x", TraceRecorder.NOOP);

        final var erro = assertThrows(IllegalArgumentException.class, () ->
                trace.emit("s001", null, "harness.agent", AgentTrace.CAT_AGENT,
                        "ok", "target.resolution.started", null, Map.of()));

        assertTrue(erro.getMessage().contains("não está na taxonomia declarada"),
                erro.getMessage());
        assertTrue(erro.getMessage().contains("target.resolution.started"));
    }

    @Test
    void aMensagemDeErroENSINAoQueFazer() {
        // Erro que só diz "inválido" faz a pessoa contornar. Este diz onde declarar
        // e por que o catálogo existe.
        final var erro = assertThrows(IllegalArgumentException.class,
                () -> HarnessEvents.require("inventado.qualquer"));

        assertTrue(erro.getMessage().contains("HarnessEvents.CLOSED"));
        assertTrue(erro.getMessage().contains("catálogo fechado que cresce sozinho"));
    }

    @Test
    void osNomesDeclaradosPASSAM() {
        final var trace = new AgentTrace("run_y", TraceRecorder.NOOP);

        for (final var nome : HarnessEvents.CLOSED) {
            trace.emit("s001", null, "harness.agent", AgentTrace.CAT_AGENT,
                    "ok", nome, null, Map.of());
        }
    }
}
