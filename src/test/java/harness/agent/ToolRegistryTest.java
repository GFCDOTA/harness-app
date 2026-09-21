package harness.agent;

import harness.agent.domain.Risk;
import harness.agent.domain.ToolCall;
import harness.agent.domain.ToolRegistry;
import harness.agent.domain.ToolSpec;
import harness.agent.domain.UnsupportedCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O portão: o que passa, o que não passa, e o que precisa de gente. */
class ToolRegistryTest {

    private static ToolSpec spec(final String name, final Risk risk) {
        return new ToolSpec(name, "d", Map.of("type", "object"), Map.of(),
                "NONE", true, risk, false, true, List.of(), 30);
    }

    private final ToolRegistry registry = new ToolRegistry(
            List.of(spec("move_object", Risk.LOW), spec("delete_object", Risk.HIGH)),
            List.of(new UnsupportedCapability("render", "sobe o SketchUp em lote; slice 5")));

    @Test
    void toolDeclaradaDeRiscoBaixoPassa() {
        var a = registry.admit(new ToolCall("move_object", Map.of()), false);
        assertTrue(a.allowed());
    }

    @Test
    void toolNaoDeclaradaEbarradaEalistaDoQueExisteVaiNoErro() {
        var a = registry.admit(new ToolCall("exec_shell", Map.of()), false);
        assertEquals(ToolRegistry.Admission.Verdict.REJECTED, a.verdict());
        assertTrue(a.message().contains("move_object"),
                "o modelo precisa ver o que EXISTE para escolher outra coisa");
    }

    @Test
    void capabilityAindaNaoSuportadaEbarradaComOmotivo() {
        var a = registry.admit(new ToolCall("render", Map.of()), false);
        assertEquals(ToolRegistry.Admission.Verdict.REJECTED, a.verdict());
        assertTrue(a.message().contains("slice 5"));
    }

    @Test
    void riscoAltoPedeConfirmacaoEmVezDeExecutar() {
        var a = registry.admit(new ToolCall("delete_object", Map.of()), false);
        assertEquals(ToolRegistry.Admission.Verdict.NEEDS_CONFIRMATION, a.verdict());
        assertFalse(a.allowed());
    }

    @Test
    void riscoAltoPassaSoQuandoAPoliticaAutoriza() {
        var a = registry.admit(new ToolCall("delete_object", Map.of()), true);
        assertTrue(a.allowed());
    }

    @Test
    void registryVazioEdetectavel() {
        assertTrue(new ToolRegistry(List.of(), List.of()).isEmpty());
        assertFalse(registry.isEmpty());
    }

    @Test
    void riscoDesconhecidoCaiEmLOWemVezDeExplodir() {
        assertEquals(Risk.LOW, Risk.of("inventado"));
        assertEquals(Risk.LOW, Risk.of(null));
        assertEquals(Risk.HIGH, Risk.of("high"));
    }

    @Test
    void nomeInventadoGanhaSugestaoDirigidaAntesDaLista() {
        // Rodando o app, o modelo chutou `open_skp` (a real e
        // `open_skp_in_sketchup`) e `save_skp`, quatro vezes cada, ignorando a
        // lista de 35 nomes que o erro devolvia. Sugestao dirigida ele usa.
        final var reg = new ToolRegistry(
                List.of(spec("open_skp_in_sketchup", Risk.LOW),
                        spec("apply_to_skp", Risk.MEDIUM),
                        spec("move_object", Risk.LOW)),
                List.of());

        final var a = reg.admit(new ToolCall("open_skp", Map.of()), false);

        assertFalse(a.allowed());
        assertEquals("UNKNOWN_TOOL", a.code());
        assertTrue(a.message().contains("Você quis dizer: open_skp_in_sketchup"),
                "a sugestao tem que vir ANTES da lista: " + a.message());
    }

    @Test
    void semParecidoNenhumNaoInventaSugestao() {
        final var reg = new ToolRegistry(List.of(spec("move_object", Risk.LOW)), List.of());

        final var a = reg.admit(new ToolCall("xyz", Map.of()), false);

        assertFalse(a.message().contains("Você quis dizer"),
                "sugestao errada confunde mais que a lista: " + a.message());
    }
}
