package inspector.domain;

/**
 * QUEM executa e SOB QUE REGRA — a camada "por baixo do capô".
 *
 * <p>Separado de {@link ComponentProfile} de propósito: perfil responde "o que é
 * isso" (explicação); isto responde "qual código roda" (fato conferível).
 *
 * @param decision    de que natureza é a decisão deste passo. Serve para o Felipe ver
 *                    que "usar IA" não significa que tudo é IA.
 * @param pattern     nome de padrão arquitetural. É EXPLICAÇÃO, não fato conferido:
 *                    o código não se declara Adapter, quem diz isso sou eu.
 * @param llmInvolved participa um modelo de linguagem nesta decisão?
 */
public record ExecutionFacts(
        Implementation impl,
        String decision,
        String pattern,
        boolean llmInvolved
) {
    public static final String IA = "IA";
    public static final String RAG = "RAG";
    public static final String ALGORITMO = "ALGORITMO";
    public static final String DETERMINISTICO = "REGRA DETERMINISTICA";
    public static final String SERVICO = "SERVICO";

    public static final ExecutionFacts NONE =
            new ExecutionFacts(Implementation.NONE, "", "", false);
}
