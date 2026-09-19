package inspector.source;

import inspector.domain.Implementation;
import inspector.domain.SourceVerification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Confere o que o catálogo AFIRMA sobre o código contra o código de verdade.
 *
 * <p>É o que separa "verificado" de "eu acho". Sem isto, o painel de implementação
 * seria só uma opinião minha bem formatada — e o pior tipo de erro numa ferramenta
 * de estudo é o que parece confiável.
 *
 * <p>Verifica três coisas, todas por leitura do arquivo:
 * <ol>
 *   <li>o módulo existe no disco;</li>
 *   <li>o símbolo aparece como {@code def <symbol>} ou {@code class <symbol>};</li>
 *   <li>cada biblioteca declarada aparece como import naquele módulo.</li>
 * </ol>
 *
 * <p>Repo ausente devolve {@code checked=false} — "não verificado" e "não existe"
 * são coisas diferentes, e confundi-las mentiria nos dois sentidos.
 */
public final class SourceVerifier {

    private final Path repoRoot;

    public SourceVerifier(final Path repoRoot) {
        this.repoRoot = repoRoot == null ? null : repoRoot.toAbsolutePath().normalize();
    }

    public Path repoRoot() {
        return this.repoRoot;
    }

    public SourceVerification verify(final Implementation impl) {
        if (impl == null || impl.isEmpty()) {
            return SourceVerification.notChecked("sem implementação declarada");
        }
        if (this.repoRoot == null || !Files.isDirectory(this.repoRoot)) {
            return SourceVerification.notChecked("repositório do pipeline não encontrado");
        }
        final var file = this.repoRoot.resolve(impl.module());
        if (!Files.isRegularFile(file)) {
            return new SourceVerification(true, false, false, List.of(), impl.libraries(),
                    "arquivo não existe em " + this.repoRoot);
        }
        final String src;
        try {
            src = Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            return SourceVerification.notChecked("não consegui ler " + impl.module());
        }

        final var symbolFound = impl.symbol() == null || impl.symbol().isBlank()
                || declares(src, impl.symbol());

        final var found = new ArrayList<String>();
        final var missing = new ArrayList<String>();
        for (final var lib : impl.libraries()) {
            if (imports(src, lib)) {
                found.add(lib);
            } else {
                missing.add(lib);
            }
        }
        final var note = missing.isEmpty() ? "" : "declarei bibliotecas que o módulo não importa";
        return new SourceVerification(true, true, symbolFound, found, missing, note);
    }

    /** {@code def nome(} ou {@code class nome} em qualquer indentação. */
    static boolean declares(final String src, final String symbol) {
        final var q = Pattern.quote(symbol);
        return Pattern.compile("(?m)^\\s*(def|class)\\s+" + q + "\\b").matcher(src).find();
    }

    /** {@code import lib}, {@code from lib import ...} ou {@code import a.lib}. */
    static boolean imports(final String src, final String lib) {
        final var q = Pattern.quote(lib);
        return Pattern.compile("(?m)^\\s*(from\\s+" + q + "\\b|import\\s+[\\w.]*\\b" + q + "\\b)")
                .matcher(src).find();
    }
}
