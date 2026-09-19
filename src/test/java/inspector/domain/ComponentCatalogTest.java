package inspector.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O catalogo e a camada de ENSINO. Estes testes travam o que ele promete: rotulo
 * honesto sobre onde a coisa roda, e silencio quando nao se sabe.
 */
class ComponentCatalogTest {

    @Test
    void ollamaEqdrantSaoLocais_naoInternet() {
        assertEquals("HTTP local", ComponentCatalog.profileFor("ollama.nomic-embed-text").kindLabel());
        assertEquals("Docker local", ComponentCatalog.profileFor("qdrant.rag_chunks").kindLabel());
        for (final String c : new String[]{"ollama.nomic-embed-text", "qdrant.rag_chunks", "ollama.deepseek-r1:14b"}) {
            assertNotEquals("HTTP internet", ComponentCatalog.profileFor(c).kindLabel(),
                    c + " roda nesta maquina");
        }
    }

    @Test
    void oLlmGanhaRotuloProprioPorqueEhOqueMaisConfunde() {
        assertEquals("LLM local", ComponentCatalog.profileFor("ollama.deepseek-r1:14b").kindLabel());
    }

    @Test
    void aRegraMaisEspecificaVenceAmaisGeral() {
        // "ollama." tambem casaria; a entrada do deepseek vem antes e precisa vencer
        assertEquals("Executar o LLM", ComponentCatalog.profileFor("ollama.deepseek-r1:14b").humanName());
        assertEquals("Gerar embedding", ComponentCatalog.profileFor("ollama.nomic-embed-text").humanName());
        assertEquals("Buscar referências", ComponentCatalog.profileFor("reference_db.retrieve").humanName());
        assertEquals("Busca estruturada (o plano B)", ComponentCatalog.profileFor("reference_db.faceted").humanName());
    }

    @Test
    void aCamadaDeRetrievalNaoEhOhBancoVetorial() {
        ComponentProfile retrieve = ComponentCatalog.profileFor("reference_db.retrieve");
        assertEquals(ComponentProfile.CODIGO_LOCAL, retrieve.kind(),
                "reference_db.retrieve e orquestracao em Python, nao o banco");
        assertTrue(retrieve.why().contains("NÃO é o banco vetorial"), retrieve.why());

        ComponentProfile qdrant = ComponentCatalog.profileFor("qdrant.rag_chunks");
        assertEquals(ComponentProfile.DOCKER_LOCAL, qdrant.kind());
    }

    @Test
    void osTresGatesCaemNaMesmaEntradaPelaFamilia() {
        for (final String g : new String[]{"gate.opening_host", "gate.wall_overlap", "gate.run_deterministic_gates"}) {
            assertEquals("Gate determinístico", ComponentCatalog.profileFor(g).humanName(), g);
            assertEquals(ComponentProfile.CODIGO_LOCAL, ComponentCatalog.profileFor(g).kind(), g);
        }
    }

    @Test
    void componenteDesconhecidoNaoGanhaExplicacaoInventada() {
        ComponentProfile p = ComponentCatalog.profileFor("coisa.que.nao.existe");
        assertFalse(p.catalogued());
        assertEquals("", p.role(), "sem catalogo, nao se inventa funcao");
        assertEquals("", p.why(), "sem catalogo, nao se inventa justificativa");
        assertTrue(p.concepts().isEmpty());
        assertEquals("coisa.que.nao.existe", p.humanName(), "cai para o nome tecnico");
    }

    @Test
    void componenteNuloNaoDerruba() {
        assertDoesNotThrow(() -> ComponentCatalog.profileFor(null));
        assertFalse(ComponentCatalog.profileFor(null).catalogued());
    }

    @Test
    void todoPerfilCatalogadoRespondeAsCincoPerguntas() {
        for (final String c : new String[]{"ollama.nomic-embed-text", "ollama.deepseek-r1:14b",
                "qdrant.rag_chunks", "reference_db.retrieve", "reference_db.faceted",
                "gate.opening_host", "architect_program", "correction_loop"}) {
            ComponentProfile p = ComponentCatalog.profileFor(c);
            assertTrue(p.catalogued(), c);
            assertFalse(p.humanName().isBlank(), "o que e? " + c);
            assertFalse(p.host().isBlank(), "onde roda? " + c);
            assertFalse(p.transport().isBlank(), "como e chamado? " + c);
            assertFalse(p.input().isBlank(), "o que recebe? " + c);
            assertFalse(p.output().isBlank(), "o que devolve? " + c);
            assertFalse(p.why().isBlank(), "por que existe? " + c);
            assertFalse(p.concepts().isEmpty(), "conceito para estudo? " + c);
        }
    }

    @Test
    void servicoLocalTemEndpointEcodigoLocalNaoTem() {
        assertEquals("localhost:11434", ComponentCatalog.profileFor("ollama.nomic-embed-text").endpoint());
        assertEquals("localhost:6333", ComponentCatalog.profileFor("qdrant.rag_chunks").endpoint());
        assertEquals("", ComponentCatalog.profileFor("reference_db.retrieve").endpoint(),
                "funcao Python no mesmo processo nao tem porta");
    }
}
