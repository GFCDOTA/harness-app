package inspector;

import inspector.domain.GptConsult;
import inspector.source.GptConsultLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    void tituloEDataSaemDoConteudoEdoNome() {
        assertEquals("GPT response — formato_antigo", byId("20260101T101010Z_formato_antigo.md").title());
        assertEquals("20260101T101010Z", byId("20260101T101010Z_formato_antigo.md").when());
        assertEquals("2026-02-02", byId("GPT_20260202_formato_novo.md").when());
    }

    @Test
    void maisRecentesPrimeiro() {
        assertEquals("GPT_20260202_formato_novo.md", read().getFirst().id());
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
