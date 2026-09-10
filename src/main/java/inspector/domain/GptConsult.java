package inspector.domain;

/**
 * Uma consulta ao oráculo GPT, como ficou registrada em disco.
 *
 * <p>{@code question} e {@code answer} podem vir vazios: o formato antigo guarda a
 * pergunta num arquivo separado, e nem todo registro tem as duas metades. Vazio é
 * vazio — o painel mostra o que existe em vez de inventar o que falta.
 */
public record GptConsult(
        String id,
        String title,
        String when,
        long sizeBytes,
        String question,
        String answer
) {
    public GptConsult {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id é obrigatório");
        question = question == null ? "" : question;
        answer = answer == null ? "" : answer;
    }

    public boolean hasBothSides() {
        return !question.isBlank() && !answer.isBlank();
    }
}
