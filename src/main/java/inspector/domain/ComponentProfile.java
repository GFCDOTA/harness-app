package inspector.domain;

import java.util.List;

/**
 * O que um componente É — não o que ele fez nesta execução.
 *
 * <p>É a camada que faltava: o trace responde "o que aconteceu"; isto responde
 * "o que é essa coisa, onde ela roda, como é chamada, o que recebe e o que devolve".
 *
 * <p>Conteúdo DETERMINÍSTICO, escrito à mão. Não chama LLM: uma ferramenta de estudo
 * que inventa explicação é pior que uma que não explica.
 *
 * @param catalogued {@code false} quando o componente não está no catálogo. Nesse
 *                   caso os campos humanos vêm vazios em vez de chutados — a UI
 *                   mostra o que dá para derivar e diz que não conhece o resto.
 */
public record ComponentProfile(
        String component,
        boolean catalogued,
        String humanName,
        String kind,
        String kindLabel,
        String transport,
        String host,
        String endpoint,
        String role,
        String input,
        String output,
        String why,
        String code,
        List<String> concepts) {
    /** Chip do card: rótulo curto e honesto sobre ONDE aquilo roda. */
    public static final String CODIGO_LOCAL = "codigo local";
    public static final String HTTP_LOCAL = "http local";
    public static final String DOCKER_LOCAL = "docker local";
    public static final String LLM_LOCAL = "llm local";
    public static final String HTTP_INTERNET = "http internet";

    public ComponentProfile {
        concepts = concepts == null ? List.of() : List.copyOf(concepts);
    }
}
