package harness.agent.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A PROCEDÊNCIA de uma resolução de alvo. É isto que o Inspector mostra.
 *
 * <p>Deliberadamente SEM `confidence` numérica. Um número que o modelo produz não
 * é auditável — "0.31" não diz por que, não se reproduz e convida a virar
 * threshold mágico. O estado é semântico e cada um mapeia numa regra que se pode
 * ler: {@link Status}.
 *
 * <p>Quem pergunta "o que o Felipe pediu, o que o modelo entendeu, que objetos
 * existiam, por que este foi escolhido, que regra bloqueou" responde tudo por
 * este record.
 */
public record TargetResolution(
        Status status,
        String proposedId,
        String selectedId,
        Set<String> roomsNamed,
        List<Candidate> candidates,
        String reason) {

    public enum Status {
        /** O proposto está no cômodo nomeado e casa com o objeto nomeado. */
        EXACT,
        /** Casou, mas por nome aproximado dentro do cômodo já validado. */
        ALIAS_MATCH,
        /** Mais de um candidato válido e nenhum id explícito — quem decide é o Felipe. */
        AMBIGUOUS,
        /** O id proposto não existe na cena. */
        NOT_FOUND,
        /** O proposto está em OUTRO cômodo que o comando nomeou. Nunca é fallback. */
        ROOM_MISMATCH,
        /** O comando nomeou um objeto e o proposto é outra coisa. */
        KIND_MISMATCH,
        /** Sem índice de cena disponível — não afirmar nada. */
        UNKNOWN;

        /** Só EXACT e ALIAS_MATCH autorizam mutação. O resto bloqueia. */
        public boolean allowsMutation() {
            return this == EXACT || this == ALIAS_MATCH || this == UNKNOWN;
        }
    }

    public record Candidate(String id, String room, boolean roomOk, boolean kindOk) {
        public Map<String, Object> toMeta() {
            return Map.of("id", this.id, "room", this.room,
                    "roomOk", this.roomOk, "kindOk", this.kindOk);
        }
    }

    public static TargetResolution unknown(final String proposedId) {
        return new TargetResolution(Status.UNKNOWN, proposedId, proposedId,
                Set.of(), List.of(), "indice de cena indisponivel");
    }

    public boolean blocked() {
        return !this.status.allowsMutation();
    }

    /** Achatado para o envelope de trace — é o que a tela de procedência lê. */
    public Map<String, Object> toMeta() {
        final var meta = new LinkedHashMap<String, Object>();
        meta.put("status", this.status.name());
        meta.put("proposedId", this.proposedId == null ? "" : this.proposedId);
        meta.put("selectedId", this.selectedId == null ? "" : this.selectedId);
        meta.put("roomsNamed", List.copyOf(this.roomsNamed));
        meta.put("candidateCount", this.candidates.size());
        meta.put("candidates", this.candidates.stream().limit(8)
                .map(Candidate::toMeta).toList());
        meta.put("reason", this.reason == null ? "" : this.reason);
        return meta;
    }
}
