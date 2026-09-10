package inspector.domain;

/**
 * Uma consulta ao oráculo GPT, como ficou registrada em disco.
 *
 * <p>{@code question} e {@code answer} podem vir vazios: o formato antigo guarda a
 * pergunta num arquivo separado, e nem todo registro tem as duas metades. Vazio é
 * vazio — o painel mostra o que existe em vez de inventar o que falta.
 *
 * <p>Há DUAS noções de quando, e elas não são a mesma coisa:
 * <ul>
 *   <li>{@code when} — instante ISO da última escrita do arquivo. Sempre presente e
 *       sempre comparável, então é por ele que a lista ordena e é ele que a UI
 *       formata;</li>
 *   <li>{@code declaredWhen} — o que o próprio documento diz ser sua data. Pode vir
 *       vazio, pode vir em formato diferente entre os dois layouts de registro, e por
 *       isso NÃO serve de chave de ordenação.</li>
 * </ul>
 * Ordenar pelo nome do arquivo já colocou um registro de ontem acima de um de hoje,
 * porque os nomes novos começam com letra e os antigos com dígito.
 */
public record GptConsult(
        String id,
        String title,
        String when,
        String declaredWhen,
        long sizeBytes,
        String question,
        String answer
) {
    public GptConsult {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id é obrigatório");
        question = question == null ? "" : question;
        answer = answer == null ? "" : answer;
        declaredWhen = declaredWhen == null ? "" : declaredWhen;
    }

    public boolean hasBothSides() {
        return !question.isBlank() && !answer.isBlank();
    }
}
