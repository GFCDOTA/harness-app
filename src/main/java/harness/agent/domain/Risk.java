package harness.agent.domain;

/**
 * Classificação de risco de uma capability. Governa o que o agente pode fazer
 * sozinho.
 *
 * <p>A régua (CLAUDE.md §risco): {@link #LOW} é reversível e barato; {@link #MEDIUM}
 * muda o projeto de forma perceptível mas ainda dá para voltar; {@link #HIGH} é
 * irreversível ou estrutural e por isso NUNCA roda sem uma pessoa dizer "pode".
 */
public enum Risk {
    LOW, MEDIUM, HIGH;

    public static Risk of(final String raw) {
        if (raw == null) return LOW;
        return switch (raw.trim().toUpperCase()) {
            case "MEDIUM" -> MEDIUM;
            case "HIGH" -> HIGH;
            default -> LOW;
        };
    }
}
