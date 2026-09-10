package inspector;

import inspector.domain.GptConsult;
import inspector.source.GptConsultLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GptConsultLogTest {

    private static List<GptConsult> read() {
        return new GptConsultLog(Paths.get("src", "test", "resources", "consults")).readAll();
    }

    private static GptConsult byId(String id) {
        return read().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void leOsDoisFormatos() {
        assertEquals(2, read().size());
    }

    @Test
    void formatoNovoTemOsDoisLados() {
        GptConsult c = byId("GPT_20260202_formato_novo.md");
        assertTrue(c.question().contains("pergunta do formato novo"), c.question());
        assertTrue(c.answer().contains("resposta do formato novo"), c.answer());
        assertTrue(c.hasBothSides());
    }

    @Test
    void formatoAntigoAchaARespostaPelaSecaoRawResponse() {
        GptConsult c = byId("20260101T101010Z_formato_antigo.md");
        assertTrue(c.answer().contains("Verdict"), c.answer());
    }

    @Test
    void ponteiroQuebradoNaoDerrubaOLeitor() {
        GptConsult c = byId("20260101T101010Z_formato_antigo.md");
        // o arquivo de pergunta nao existe; o painel mostra a metade que existe
        assertNotNull(c.question());
        assertTrue(c.answer().contains("Verdict"));
    }

    @Test
    void tituloSaiDoConteudo() {
        assertEquals("GPT response — formato_antigo", byId("20260101T101010Z_formato_antigo.md").title());
    }

    @Test
    void aDataDeclaradaVemDoDocumento() {
        assertEquals("20260101T101010Z", byId("20260101T101010Z_formato_antigo.md").declaredWhen());
        assertEquals("2026-02-02", byId("GPT_20260202_formato_novo.md").declaredWhen());
    }

    @Test
    void whenEhInstanteIsoDoArquivoParaOrdenarEformatar() {
        for (GptConsult c : read()) {
            assertDoesNotThrow(() -> java.time.Instant.parse(c.when()),
                    "when precisa ser ISO comparavel, veio: " + c.when());
        }
    }

    @Test
    void ordenaPorDataDeEscritaEnaoPeloNomeDoArquivo(@TempDir Path dir) throws Exception {
        // Reproduz o bug real: nome que comeca com LETRA vencia nome que comeca com
        // DIGITO na ordem descendente, jogando um registro antigo para o topo.
        Path antigoComLetra = dir.resolve("SPIKE_20260909_antigo.md");
        Path novoComDigito = dir.resolve("20260910T120000Z_novo.md");
        Files.writeString(antigoComLetra, "# antigo", StandardCharsets.UTF_8);
        Files.writeString(novoComDigito, "# novo", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(antigoComLetra, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(novoComDigito, FileTime.fromMillis(9_000_000));

        assertEquals("20260910T120000Z_novo.md", new GptConsultLog(dir).readAll().getFirst().id(),
                "o mais recente tem que vir primeiro, mesmo com nome 'menor'");
    }

    @Test
    void diretorioInexistenteDevolveVazioEmVezDeExplodir(@TempDir Path dir) {
        assertTrue(new GptConsultLog(dir.resolve("nao_existe")).readAll().isEmpty());
    }

    @Test
    void arquivoSemSecaoConhecidaUsaOCorpoInteiroComoResposta(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("solto.md"), "# titulo\n\nso texto corrido", StandardCharsets.UTF_8);
        GptConsult c = new GptConsultLog(dir).readAll().getFirst();
        assertTrue(c.answer().contains("so texto corrido"));
        assertFalse(c.hasBothSides(), "sem pergunta, nao sao dois lados");
    }
}
