package harness.agent;

import harness.agent.domain.SceneIndex;
import harness.agent.domain.TargetResolution;
import harness.agent.domain.TargetResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O gate de resolução de alvo, contra a cena REAL da planta_74.
 *
 * <p>Nasceu de um bug que reportou sucesso: "pinta o armário da COZINHA de
 * verde-escuro" pintou o SOFÁ DA SALA e o desfecho saiu CLEAN. Aqui as regras
 * duras ficam travadas, e o teste do bug é o primeiro.
 *
 * <p>A outra metade importa igual: comando vago NÃO pode ser bloqueado. Guarda
 * que trava pedido legítimo também é defeito, só que mais chato de descobrir.
 */
class TargetResolverTest {

    /** Recorte fiel da planta_74 — nomes e ids são os de verdade. */
    private static SceneIndex planta74() {
        return new SceneIndex(
                List.of(new SceneIndex.Room("r000", "SUITE 01"),
                        new SceneIndex.Room("r001", "A.S. | TERRACO SOCIAL | TERRACO TECNICO"),
                        new SceneIndex.Room("r002", "SALA DE JANTAR | SALA DE ESTAR"),
                        new SceneIndex.Room("r003", "SUITE 02"),
                        new SceneIndex.Room("r004", "COZINHA")),
                List.of(
                        obj("suite_01.cama", "r000", "SUITE 01", "Cama", "bed"),
                        obj("suite_01.escrivaninha", "r000", "SUITE 01", "Escrivaninha", "desk"),
                        obj("suite_01.criado_mudo_1", "r000", "SUITE 01", "Criado-mudo 1", "nightstand"),
                        obj("sala_de_jantar_sala_de_estar.sofa", "r002",
                                "SALA DE JANTAR | SALA DE ESTAR", "Sofa", "sofa"),
                        obj("cozinha.upper_cabinet_01", "r004", "COZINHA",
                                "upper_cabinet_01", "upper_cabinet"),
                        obj("cozinha.fridge", "r004", "COZINHA", "fridge", "fridge"),
                        obj("a_s_terraco_social_terraco_tecnico.armario_de_servico", "r001",
                                "A.S. | TERRACO SOCIAL | TERRACO TECNICO",
                                "Armario de servico", "armario_servico"),
                        obj("suite_02.cama", "r003", "SUITE 02", "Cama", "bed")));
    }

    private static SceneIndex.Obj obj(final String id, final String roomId, final String room,
                                      final String label, final String kind) {
        return new SceneIndex.Obj(id, roomId, room, label, List.of(kind));
    }

    // -- O BUG -------------------------------------------------------------
    @Test
    void comodoNomeadoNuncaAceitaObjetoDeOUTROcomodo() {
        final var r = TargetResolver.resolve(
                "pinta o armario da cozinha de verde-escuro",
                "sala_de_jantar_sala_de_estar.sofa", planta74());

        assertEquals(TargetResolution.Status.ROOM_MISMATCH, r.status());
        assertTrue(r.blocked());
        assertTrue(r.roomsNamed().contains("r004"), "o comando nomeia a cozinha");
        assertEquals(null, r.selectedId(), "nada pode ser selecionado num mismatch");
    }

    @Test
    void aProcedenciaMostraOScandidatosReaisEporQueCadaUmFalha() {
        final var r = TargetResolver.resolve(
                "pinta o armario da cozinha de verde-escuro",
                "sala_de_jantar_sala_de_estar.sofa", planta74());

        final var sofa = r.candidates().stream()
                .filter(c -> c.id().endsWith(".sofa")).findFirst().orElseThrow();
        assertFalse(sofa.roomOk(), "o sofa esta fora do comodo nomeado");
        assertTrue(r.candidates().stream().anyMatch(c -> c.id().startsWith("cozinha.") && c.roomOk()),
                "os objetos da cozinha tinham que aparecer como candidatos");
        assertTrue(r.reason().contains("r004") || r.reason().contains("COZINHA"));
    }

    // -- cross-kind --------------------------------------------------------
    @Test
    void objetoNomeadoNoComandoNaoResolveParaOutraCoisa() {
        // "a cama da suite 01" nomeia a Cama; propor o criado-mudo e cross-kind.
        final var r = TargetResolver.resolve(
                "pinta a cama da suite 01 de preto",
                "suite_01.criado_mudo_1", planta74());

        assertEquals(TargetResolution.Status.KIND_MISMATCH, r.status());
        assertTrue(r.blocked());
    }

    @Test
    void armarioSemComodoResolveParaOarmarioQueEXISTE() {
        // Existe "Armario de servico" na A.S. Se o modelo propuser o sofa, e
        // cross-kind mesmo sem o comando nomear comodo.
        final var r = TargetResolver.resolve(
                "pinta o armario de verde-escuro",
                "sala_de_jantar_sala_de_estar.sofa", planta74());

        assertEquals(TargetResolution.Status.KIND_MISMATCH, r.status());
    }

    // -- ambiguidade -------------------------------------------------------
    @Test
    void doisObjetosComOmesmoNomeSemComodoPERGUNTA() {
        // "a cama" casa com suite_01.cama E suite_02.cama.
        final var r = TargetResolver.resolve("pinta a cama de preto",
                "suite_01.cama", planta74());

        assertEquals(TargetResolution.Status.AMBIGUOUS, r.status());
        assertTrue(r.blocked(), "ambiguo nao escolhe silenciosamente");
    }

    @Test
    void comComodoNomeadoAcamaDeixaDeSerAmbigua() {
        final var r = TargetResolver.resolve("pinta a cama da suite 01 de preto",
                "suite_01.cama", planta74());

        assertEquals(TargetResolution.Status.EXACT, r.status());
        assertFalse(r.blocked());
        assertEquals("suite_01.cama", r.selectedId());
    }

    // -- o oposto do bug: nao bloquear o legitimo --------------------------
    @Test
    void comandoQueNaoNomeiaComodoNemObjetoNAOebloqueado() {
        // "deixa mais escuro" nao nomeia nada — restringir aqui seria travar
        // pedido legitimo. Preferir nao restringir a restringir errado.
        final var r = TargetResolver.resolve("deixa mais escuro",
                "sala_de_jantar_sala_de_estar.sofa", planta74());

        assertFalse(r.blocked());
        assertTrue(r.roomsNamed().isEmpty());
    }

    @Test
    void suiteSemNumeroNaoRestringeComodo() {
        // "suite" aparece em SUITE 01 e SUITE 02: nao e token distintivo, logo nao
        // identifica comodo e nao pode virar restricao.
        final var r = TargetResolver.resolve("pinta a escrivaninha da suite de preto",
                "suite_01.escrivaninha", planta74());

        assertTrue(r.roomsNamed().isEmpty(), "suite sozinho nao identifica comodo");
        assertFalse(r.blocked());
    }

    @Test
    void idExplicitoNoComandoEsempreEXACT() {
        final var r = TargetResolver.resolve(
                "pinta o objeto cozinha.upper_cabinet_01 de verde-escuro",
                "cozinha.upper_cabinet_01", planta74());

        assertEquals(TargetResolution.Status.EXACT, r.status());
        assertFalse(r.blocked());
    }

    @Test
    void idInexistenteEnotFoundNaoSucesso() {
        final var r = TargetResolver.resolve("pinta a cama da suite 01 de preto",
                "suite_01.nao_existe", planta74());

        assertEquals(TargetResolution.Status.NOT_FOUND, r.status());
        assertTrue(r.blocked());
    }

    @Test
    void semIndiceDeCenaNaoAFIRMAnada() {
        // Host fora, cena nao carregada: UNKNOWN nao bloqueia (nao ha base para
        // bloquear) e tambem nao afirma que esta certo.
        final var r = TargetResolver.resolve("pinta a cama de preto",
                "suite_01.cama", SceneIndex.empty());

        assertEquals(TargetResolution.Status.UNKNOWN, r.status());
        assertFalse(r.blocked());
    }

    // -- procedencia serializavel ------------------------------------------
    @Test
    void aMetaDoTraceRespondeAsPerguntasDaAuditoria() {
        final var meta = TargetResolver.resolve(
                "pinta o armario da cozinha de verde-escuro",
                "sala_de_jantar_sala_de_estar.sofa", planta74()).toMeta();

        assertEquals("ROOM_MISMATCH", meta.get("status"));
        assertEquals("sala_de_jantar_sala_de_estar.sofa", meta.get("proposedId"));
        assertEquals("", meta.get("selectedId"));
        assertTrue(meta.containsKey("candidates"));
        assertTrue(meta.containsKey("roomsNamed"));
        assertTrue(String.valueOf(meta.get("reason")).length() > 10);
    }
}
