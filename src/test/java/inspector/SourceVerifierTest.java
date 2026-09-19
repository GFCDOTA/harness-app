package inspector;

import inspector.domain.Implementation;
import inspector.domain.SourceVerification;
import inspector.source.SourceVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceVerifierTest {

    private static Path repo(final Path dir, final String rel, final String conteudo) throws Exception {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, conteudo, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void acha_modulo_simbolo_e_biblioteca(final @TempDir Path dir) throws Exception {
        repo(dir, "tools/x.py", "import urllib.request\n\ndef embed(txt):\n    return []\n");
        SourceVerification v = new SourceVerifier(dir).verify(
                new Implementation("python", "tools/x.py", "embed", List.of("urllib"), ""));
        assertTrue(v.checked());
        assertTrue(v.moduleFound());
        assertTrue(v.symbolFound());
        assertEquals(List.of("urllib"), v.librariesFound());
        assertTrue(v.fullyVerified());
    }

    @Test
    void bibliotecaDeclaradaQueOmoduloNaoImportaAparaceComoFALTANDO(final @TempDir Path dir) throws Exception {
        repo(dir, "tools/x.py", "import urllib.request\n\ndef search(q):\n    return []\n");
        SourceVerification v = new SourceVerifier(dir).verify(
                new Implementation("python", "tools/x.py", "search", List.of("qdrant_client"), ""));
        assertFalse(v.fullyVerified(), "claim errado nao pode passar por verificado");
        assertEquals(List.of("qdrant_client"), v.librariesMissing());
        assertFalse(v.note().isBlank());
    }

    @Test
    void simboloInexistenteNaoPassa(final @TempDir Path dir) throws Exception {
        repo(dir, "tools/x.py", "def outra():\n    pass\n");
        SourceVerification v = new SourceVerifier(dir).verify(
                new Implementation("python", "tools/x.py", "find_best_candidate", List.of(), ""));
        assertTrue(v.moduleFound());
        assertFalse(v.symbolFound(), "funcao que nao existe nao pode ser reportada como existente");
        assertFalse(v.fullyVerified());
    }

    @Test
    void arquivoInexistenteEhReportadoComoNaoEncontrado(final @TempDir Path dir) {
        SourceVerification v = new SourceVerifier(dir).verify(
                new Implementation("python", "tools/fantasma.py", "x", List.of(), ""));
        assertTrue(v.checked());
        assertFalse(v.moduleFound());
    }

    @Test
    void repoAusenteEhNAO_VERIFICADO_naoNAO_EXISTE(final @TempDir Path dir) {
        SourceVerification v = new SourceVerifier(dir.resolve("nao_existe")).verify(
                new Implementation("python", "tools/x.py", "y", List.of(), ""));
        assertFalse(v.checked(), "sem repo, a UI precisa dizer 'nao verificado'");
        assertFalse(v.fullyVerified());
    }

    @Test
    void reconheceClasseAlemDeFuncao(final @TempDir Path dir) throws Exception {
        repo(dir, "m.py", "class InfraUnavailable(RuntimeError):\n    pass\n");
        assertTrue(new SourceVerifier(dir).verify(
                new Implementation("python", "m.py", "InfraUnavailable", List.of(), "")).symbolFound());
    }

    @Test
    void naoConfundeSubstringComSimbolo(final @TempDir Path dir) throws Exception {
        repo(dir, "m.py", "def embedding_helper():\n    pass\n");
        assertFalse(new SourceVerifier(dir).verify(
                new Implementation("python", "m.py", "embed", List.of(), "")).symbolFound(),
                "embed nao esta definido; embedding_helper e outra funcao");
    }

    @Test
    void reconheceFromImportEimportPontuado(final @TempDir Path dir) throws Exception {
        repo(dir, "m.py", "from sqlite3 import connect\nimport urllib.request\ndef f():\n    pass\n");
        SourceVerifier v = new SourceVerifier(dir);
        assertTrue(v.verify(new Implementation("python", "m.py", "f", List.of("sqlite3"), "")).fullyVerified());
        assertTrue(v.verify(new Implementation("python", "m.py", "f", List.of("urllib"), "")).fullyVerified());
    }
}
