package inspector.domain;

import java.util.List;

/**
 * O resultado de conferir uma {@link Implementation} contra o código de verdade.
 *
 * <p>Existe para a UI poder separar <b>verificado no código</b> de <b>explicação
 * educacional</b>. Sem essa separação, o painel vira uma fonte confiante de coisas
 * que ninguém checou.
 *
 * @param checked        {@code false} quando nem deu para procurar (repo ausente).
 *                       Nesse caso a UI diz "não verificado", não "não existe".
 * @param librariesFound bibliotecas declaradas E encontradas como import no módulo
 * @param librariesMissing bibliotecas declaradas e NÃO encontradas — claim errado meu
 */
public record SourceVerification(
        boolean checked,
        boolean moduleFound,
        boolean symbolFound,
        List<String> librariesFound,
        List<String> librariesMissing,
        String note) {
    public SourceVerification {
        librariesFound = librariesFound == null ? List.of() : List.copyOf(librariesFound);
        librariesMissing = librariesMissing == null ? List.of() : List.copyOf(librariesMissing);
    }

    public static SourceVerification notChecked(final String why) {
        return new SourceVerification(false, false, false, List.of(), List.of(), why);
    }

    /** Verde só quando o arquivo E o símbolo existem E nenhuma lib ficou faltando. */
    public boolean fullyVerified() {
        return checked && moduleFound && symbolFound && librariesMissing.isEmpty();
    }
}
