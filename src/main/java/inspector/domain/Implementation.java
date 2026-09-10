package inspector.domain;

import java.util.List;

/**
 * QUEM executa um passo, em termos de código real.
 *
 * <p>Estes campos são AFIRMAÇÕES SOBRE O CÓDIGO e por isso precisam ser conferidos
 * contra o repositório antes de aparecerem como fato — ver {@link SourceVerifier}.
 * A alternativa seria trocar uma interface vaga por uma interface confiante e errada.
 *
 * <p>Repare que aqui não há "classe": o pipeline Python do sketchup-mcp é FUNCIONAL.
 * {@code tools/reference_db.py} não tem uma única classe. Modelar isto como
 * classe+método produziria nomes que não existem.
 *
 * @param libraries bibliotecas que o módulo realmente importa. Para Qdrant e Ollama
 *                  isso é {@code urllib} da stdlib — o repo NÃO usa qdrant-client.
 */
public record Implementation(
        String language,
        String module,
        String symbol,
        List<String> libraries,
        String runtime
) {
    public Implementation {
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
    }

    public static final Implementation NONE =
            new Implementation("", "", "", List.of(), "");

    public boolean isEmpty() {
        return module == null || module.isBlank();
    }
}
