package harness.agent.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decide, DETERMINISTICAMENTE, se o objeto que o modelo escolheu é o que o Felipe
 * pediu.
 *
 * <p>Veio de um bug real: o comando era "pinta o armário da COZINHA de
 * verde-escuro" e o Harness pintou o SOFÁ DA SALA, reportando `CLEAN`. O modelo
 * pode propor; a autoridade sobre o alvo é esta classe, que consulta o estado
 * real da cena ({@link SceneIndex}).
 *
 * <p>As regras duras, nesta ordem:
 *
 * <ol>
 *   <li><b>Nunca fallback cross-room.</b> Se o comando nomeia a cozinha, um
 *       candidato da sala jamais serve.
 *   <li><b>Nunca fallback cross-kind.</b> Se o comando nomeia um objeto que
 *       existe, o proposto não pode ser outra coisa.
 *   <li><b>Ambíguo pergunta.</b> Mais de um candidato válido e nenhum id
 *       explícito não vira escolha silenciosa.
 * </ol>
 *
 * <p>Princípio que evita o oposto do bug: quando o comando <b>não</b> nomeia
 * cômodo nem objeto, o resolver NÃO restringe. Preferir não restringir a
 * restringir errado — bloquear comando legítimo também é defeito.
 */
public final class TargetResolver {

    private TargetResolver() {
    }

    public static TargetResolution resolve(final String command, final String proposedId,
                                           final SceneIndex index) {
        if (index == null || index.isEmpty()) {
            return TargetResolution.unknown(proposedId);
        }
        if (proposedId == null || proposedId.isBlank()) {
            return TargetResolution.unknown(proposedId);
        }
        final var proposed = index.object(proposedId);
        final var roomsNamed = index.roomsNamedIn(command);
        final var nomeados = index.objectsNamedIn(command, roomsNamed);
        final var candidatos = candidates(index, roomsNamed, nomeados, proposedId);

        if (proposed == null) {
            // Id que nao existe na cena. Nao e o caso do bug, mas mentir "ok" aqui
            // deixaria o erro aparecer so no handler, sem procedencia.
            return new TargetResolution(TargetResolution.Status.NOT_FOUND, proposedId, null,
                    roomsNamed, candidatos,
                    "o id proposto nao existe na cena");
        }
        // REGRA 1 — cross-room nunca.
        if (!roomsNamed.isEmpty() && !roomsNamed.contains(proposed.roomId())) {
            return new TargetResolution(TargetResolution.Status.ROOM_MISMATCH, proposedId, null,
                    roomsNamed, candidatos,
                    "o comando nomeia " + String.join("/", roomsNamed)
                            + " e o objeto proposto esta em " + proposed.roomId()
                            + " (" + proposed.room() + ")");
        }
        // REGRA 2 — cross-kind nunca, quando o comando nomeia um objeto que existe.
        if (!nomeados.isEmpty() && nomeados.stream().noneMatch(o -> o.id().equals(proposedId))) {
            return new TargetResolution(TargetResolution.Status.KIND_MISMATCH, proposedId, null,
                    roomsNamed, candidatos,
                    "o comando nomeia " + nomeados.get(0).label()
                            + " e o objeto proposto e " + proposed.label());
        }
        // REGRA 3 — ambiguo pergunta, nao escolhe.
        if (nomeados.size() > 1 && !command.toLowerCase().contains(proposedId.toLowerCase())) {
            return new TargetResolution(TargetResolution.Status.AMBIGUOUS, proposedId, null,
                    roomsNamed, candidatos,
                    nomeados.size() + " objetos casam com o que o comando nomeia");
        }
        final var exato = command.toLowerCase().contains(proposedId.toLowerCase())
                || nomeados.size() == 1;
        return new TargetResolution(
                exato ? TargetResolution.Status.EXACT : TargetResolution.Status.ALIAS_MATCH,
                proposedId, proposedId, roomsNamed, candidatos,
                exato ? "id explicito ou objeto unico no comando"
                      : "dentro do comodo nomeado, sem objeto concorrente");
    }

    /**
     * Candidatos para a tela de procedência: o que existia de verdade e por quê
     * cada um casa ou não. Inclui o proposto mesmo quando ele é o errado — é
     * justamente o que se quer ver.
     */
    private static List<TargetResolution.Candidate> candidates(
            final SceneIndex index, final Set<String> roomsNamed,
            final List<SceneIndex.Obj> nomeados, final String proposedId) {
        final var out = new ArrayList<TargetResolution.Candidate>();
        final var nomeadosIds = nomeados.stream().map(SceneIndex.Obj::id).toList();
        final var pool = new ArrayList<SceneIndex.Obj>();
        if (!roomsNamed.isEmpty()) {
            index.objects().stream().filter(o -> roomsNamed.contains(o.roomId()))
                    .forEach(pool::add);
        } else {
            pool.addAll(nomeados);
        }
        final var proposto = index.object(proposedId);
        if (proposto != null && pool.stream().noneMatch(o -> o.id().equals(proposedId))) {
            pool.add(proposto);
        }
        for (final var obj : pool) {
            final var roomOk = roomsNamed.isEmpty() || roomsNamed.contains(obj.roomId());
            final var kindOk = nomeadosIds.isEmpty() || nomeadosIds.contains(obj.id());
            out.add(new TargetResolution.Candidate(obj.id(), obj.room(), roomOk, kindOk));
        }
        return List.copyOf(out);
    }
}
