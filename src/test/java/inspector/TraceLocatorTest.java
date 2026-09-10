package inspector;

import inspector.source.TraceLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class TraceLocatorTest {

    private static Path trace(Path dir, String nome, long mtime) throws Exception {
        Path p = dir.resolve(nome);
        Files.writeString(p, "{\"runId\":\"r1\",\"seq\":1,\"name\":\"run.started\"}\n",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtime));
        return p;
    }

    @Test
    void arquivoExplicitoGanhaDeTodoOResto(@TempDir Path dir) throws Exception {
        Path escolhido = trace(dir, "escolhido.jsonl", 1000L);
        Path maisNovo = trace(dir, "mais_novo.jsonl", 9000L);
        Path r = TraceLocator.resolve(escolhido.toString(), dir.toString(), dir.toString(), dir);
        assertEquals(escolhido.getFileName(), r.getFileName());
        assertNotEquals(maisNovo.getFileName(), r.getFileName());
    }

    @Test
    void diretorioExplicitoGanhaDaVariavelDeAmbiente(@TempDir Path a, @TempDir Path b) throws Exception {
        trace(a, "do_dir.jsonl", 1000L);
        trace(b, "do_env.jsonl", 9000L);
        Path r = TraceLocator.resolve(null, a.toString(), b.toString(), null);
        assertEquals("do_dir.jsonl", r.getFileName().toString());
    }

    @Test
    void variavelDeAmbienteGanhaDoDiretorioConvencional(@TempDir Path env, @TempDir Path conv) throws Exception {
        trace(env, "do_env.jsonl", 1000L);
        trace(conv, "convencional.jsonl", 9000L);
        Path r = TraceLocator.resolve(null, null, env.toString(), conv);
        assertEquals("do_env.jsonl", r.getFileName().toString());
    }

    @Test
    void usaOConvencionalSoQuandoNadaMaisFoiInformado(@TempDir Path conv) throws Exception {
        trace(conv, "velho.jsonl", 1000L);
        trace(conv, "novo.jsonl", 9000L);
        assertEquals("novo.jsonl",
                TraceLocator.resolve(null, null, null, conv).getFileName().toString());
    }

    @Test
    void semNenhumaFonteFalhaEnsinandoAsTresOpcoes() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TraceLocator.resolve(null, null, null, null));
        String m = ex.getMessage();
        assertTrue(m.contains("-Dtrace="), m);
        assertTrue(m.contains("-DtraceDir="), m);
        assertTrue(m.contains("INSPECTOR_TRACE_DIR"), m);
    }

    @Test
    void convencionalInexistenteNaoContaComoFonte(@TempDir Path dir) {
        Path naoExiste = dir.resolve("nao_existe");
        assertThrows(IllegalStateException.class,
                () -> TraceLocator.resolve(null, null, null, naoExiste));
    }

    @Test
    void arquivoExplicitoInexistenteFalhaEmVezDeCairNoFallback(@TempDir Path conv) throws Exception {
        trace(conv, "existe.jsonl", 1000L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TraceLocator.resolve(conv.resolve("fantasma.jsonl").toString(),
                        null, null, conv));
        assertTrue(ex.getMessage().contains("não é arquivo"), ex.getMessage());
    }

    @Test
    void stringEmBrancoNaoContaComoFonteInformada(@TempDir Path conv) throws Exception {
        trace(conv, "unico.jsonl", 1000L);
        assertEquals("unico.jsonl",
                TraceLocator.resolve("  ", "", "  ", conv).getFileName().toString());
    }
}
