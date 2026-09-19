package inspector;

import inspector.domain.ExecutionFacts;
import inspector.domain.ImplementationCatalog;
import inspector.domain.SourceVerification;
import inspector.source.SourceVerifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * O teste que impede o catálogo de virar ficção.
 *
 * <p>Cada afirmação sobre o código (módulo, símbolo, biblioteca) é conferida contra o
 * repositório real do sketchup-mcp. Se eu escrever uma classe que não existe ou uma
 * lib que o módulo não importa, ISTO QUEBRA.
 *
 * <p>É opt-in porque o caminho do repo é desta máquina — mas quando o repo está lá,
 * ele roda e vale como gate.
 */
class ImplementationCatalogTest {

    private static final List<String> COMPONENTES = List.of(
            "ollama.nomic-embed-text", "ollama.deepseek-r1:14b", "qdrant.rag_chunks",
            "reference_db.retrieve", "reference_db.faceted", "reference_db.embed_recall",
            "gate.opening_host", "architect_program", "correction_loop",
            "finding_router", "correction_fixes", "inspector.demo");

    private static Path repoDoPipeline() {
        final var prop = System.getProperty("pipelineRepo");
        if (prop != null && !prop.isBlank()) return Paths.get(prop);
        return Paths.get("..", "sketchup-mcp");
    }

    @Test
    void todaAfirmacaoSobreOcodigoEhVerdadeiraNoRepoReal() {
        Path repo = repoDoPipeline();
        assumeTrue(Files.isDirectory(repo), "repo do pipeline ausente: teste pulado");

        SourceVerifier verificador = new SourceVerifier(repo);
        List<String> problemas = new ArrayList<>();

        for (final String c : COMPONENTES) {
            ExecutionFacts f = ImplementationCatalog.factsFor(c);
            assertFalse(f.impl().isEmpty(), c + " nao tem implementacao declarada");
            SourceVerification v = verificador.verify(f.impl());

            if (!v.moduleFound()) problemas.add(c + ": modulo nao existe -> " + f.impl().module());
            if (!v.symbolFound()) problemas.add(c + ": simbolo nao existe -> " + f.impl().symbol());
            if (!v.librariesMissing().isEmpty()) {
                problemas.add(c + ": libs declaradas que o modulo nao importa -> " + v.librariesMissing());
            }
        }
        assertTrue(problemas.isEmpty(),
                "o catalogo afirma coisas que o codigo nao confirma:\n  " + String.join("\n  ", problemas));
    }

    @Test
    void oPipelineNaoUsaQdrantClientNemShapely() {
        for (final String c : COMPONENTES) {
            for (final String lib : ImplementationCatalog.factsFor(c).impl().libraries()) {
                assertNotEquals("qdrant_client", lib, c + ": este repo fala com Qdrant por urllib puro");
                assertNotEquals("shapely", lib, c + ": nenhum modulo do pipeline importa shapely");
            }
        }
    }

    @Test
    void nenhumaImplementacaoDeclaraClasse_porqueOpipelineEhFuncional() {
        for (final String c : COMPONENTES) {
            final var simbolo = ImplementationCatalog.factsFor(c).impl().symbol();
            assertFalse(simbolo.contains("."),
                    c + ": simbolo com ponto sugere Classe.metodo, e este pipeline e funcional");
        }
    }

    @Test
    void aNaturezaDaDecisaoSepararIAdoRestante() {
        assertEquals(ExecutionFacts.IA, ImplementationCatalog.factsFor("ollama.deepseek-r1:14b").decision());
        assertTrue(ImplementationCatalog.factsFor("ollama.deepseek-r1:14b").llmInvolved());

        assertEquals(ExecutionFacts.DETERMINISTICO, ImplementationCatalog.factsFor("gate.opening_host").decision());
        assertFalse(ImplementationCatalog.factsFor("gate.opening_host").llmInvolved(),
                "gate deterministico nao pode aparecer como decisao de IA");

        assertFalse(ImplementationCatalog.factsFor("qdrant.rag_chunks").llmInvolved(),
                "banco vetorial busca por similaridade; quem gerou o vetor foi o modelo, nao ele");
        assertFalse(ImplementationCatalog.factsFor("correction_loop").llmInvolved());
    }

    @Test
    void componenteDesconhecidoNaoGanhaImplementacaoInventada() {
        ExecutionFacts f = ImplementationCatalog.factsFor("coisa.inexistente");
        assertTrue(f.impl().isEmpty());
        assertEquals("", f.decision());
        assertFalse(f.llmInvolved());
    }
}
