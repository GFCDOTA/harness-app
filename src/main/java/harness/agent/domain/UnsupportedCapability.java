package harness.agent.domain;

/**
 * Uma capability que a missão nomeia e que o sistema ainda NÃO faz.
 *
 * <p>Existe para ser publicada, não escondida: o agente precisa saber que
 * {@code render} não está disponível para dizer isso ao Felipe, em vez de chamar
 * um stub que devolve verde e ensinar que funcionou.
 */
public record UnsupportedCapability(String name, String reason) {
}
