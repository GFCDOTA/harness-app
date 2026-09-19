package harness.agent.domain;

import inspector.domain.TraceEvent;

/**
 * PORT — onde o trace do agente é gravado.
 *
 * <p>Decisão que evita um segundo formato de observabilidade: o agente emite o
 * MESMO envelope v1 que o Inspector já sabe ler ({@link TraceEvent}). Com isso a
 * execução de um comando aparece na Pipeline View sem nenhum código novo de
 * visualização, e existe UM contrato de trace no sistema, não dois.
 */
public interface TraceRecorder {

    void record(TraceEvent event);

    /** Chamado no fim de uma run para fechar o arquivo/stream, se houver. */
    default void close() {
    }

    /** Descarta tudo — usado quando o trace não deve ser persistido. */
    TraceRecorder NOOP = event -> {
    };
}
