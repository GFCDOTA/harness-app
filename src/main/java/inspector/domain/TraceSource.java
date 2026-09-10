package inspector.domain;

import java.util.function.Consumer;

/**
 * PORT — de onde vêm os eventos. Declarado no domínio; implementado na infra.
 *
 * <p>Um contrato só serve os dois casos reais porque ambos são "empurre eventos em
 * ordem de seq": o replay termina quando o arquivo acaba, o SSE termina quando a
 * conexão fecha. Duas implementações REAIS — é o que justifica o port existir.
 */
public interface TraceSource {

    /** Origem legível, para a UI dizer de onde está lendo. */
    String describe();

    /**
     * Empurra cada evento, em ordem de {@code seq}, para o sink.
     * Retorna quando a fonte se esgota (replay) ou é fechada (SSE).
     */
    void stream(Consumer<TraceEvent> sink);
}
