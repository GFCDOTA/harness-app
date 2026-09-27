package harness.agent.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O que EXISTE de verdade na cena: cômodos e objetos, com os nomes reais.
 *
 * <p>Existe para que a resolução de alvo pare de ser palpite do modelo. O LLM
 * pode propor "o armário da cozinha"; quem diz quais objetos existem, e em que
 * cômodo, é este índice — lido do estado real por {@code list_rooms} e
 * {@code list_objects}. Nenhuma capability nova foi criada para isso.
 *
 * <p>Sem I/O e sem framework: o índice é construído a partir de {@link ToolResult}
 * já obtidos, então o resolver e o guard são testáveis sem host nenhum.
 */
public record SceneIndex(List<Room> rooms, List<Obj> objects) {

    public record Room(String id, String name) {
    }

    public record Obj(String id, String roomId, String room, String label, List<String> kinds) {
    }

    public static SceneIndex empty() {
        return new SceneIndex(List.of(), List.of());
    }

    public boolean isEmpty() {
        return this.objects.isEmpty();
    }

    public Obj object(final String id) {
        return this.objects.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }

    /** Constrói o índice a partir dos resultados crus de `list_rooms` + `list_objects`. */
    @SuppressWarnings("unchecked")
    public static SceneIndex from(final ToolResult roomsResult, final ToolResult objectsResult) {
        final var rooms = new ArrayList<Room>();
        if (roomsResult != null && roomsResult.ok()
                && roomsResult.data().get("rooms") instanceof List<?> raw) {
            for (final var item : raw) {
                if (item instanceof Map<?, ?> m) {
                    final var id = str(m.get("id"));
                    final var name = str(m.get("name"));
                    if (!id.isBlank()) rooms.add(new Room(id, name));
                }
            }
        }
        final var objects = new ArrayList<Obj>();
        if (objectsResult != null && objectsResult.ok()
                && objectsResult.data().get("objects") instanceof List<?> raw) {
            for (final var item : raw) {
                if (item instanceof Map<?, ?> m) {
                    final var id = str(m.get("id"));
                    if (id.isBlank()) continue;
                    final var kinds = new ArrayList<String>();
                    if (m.get("kinds") instanceof List<?> ks) {
                        for (final var k : ks) kinds.add(str(k));
                    }
                    objects.add(new Obj(id, str(m.get("roomId")), str(m.get("room")),
                            str(m.get("label")), List.copyOf(kinds)));
                }
            }
        }
        return new SceneIndex(List.copyOf(rooms), List.copyOf(objects));
    }

    // -- vocabulário ---------------------------------------------------------

    /**
     * Tokens DISTINTIVOS de cada cômodo: os que aparecem no nome de um cômodo só.
     *
     * <p>É o que evita bloqueio errado. "cozinha" é distintivo e identifica o
     * cômodo; "suite" aparece em SUITE 01 e SUITE 02, então sozinho não identifica
     * nada e não vira restrição. "01" é distintivo e resolve a suíte.
     *
     * <p>Consequência deliberada: comando vago ("a cama") não nomeia cômodo, e o
     * guard não age. Preferir não restringir a restringir errado.
     */
    public Map<String, Set<String>> distinctiveRoomTokens() {
        final var byToken = new LinkedHashMap<String, Set<String>>();
        for (final var room : this.rooms) {
            for (final var token : significant(tokens(room.name()))) {
                byToken.computeIfAbsent(token, k -> new LinkedHashSet<>()).add(room.id());
            }
        }
        final var out = new LinkedHashMap<String, Set<String>>();
        byToken.forEach((token, ids) -> {
            if (ids.size() == 1) out.put(token, ids);
        });
        return out;
    }

    /** Os cômodos que o comando NOMEIA, por token distintivo. Vazio = não nomeou. */
    public Set<String> roomsNamedIn(final String command) {
        final var said = significant(tokens(command));
        final var out = new LinkedHashSet<String>();
        distinctiveRoomTokens().forEach((token, ids) -> {
            if (said.contains(token)) out.addAll(ids);
        });
        return out;
    }

    /**
     * Objetos cujo nome real (label/kind) aparece no comando.
     *
     * <p>Sem tabela de sinônimos inventada: o vocabulário sai da própria cena. Se
     * o Felipe falou "armário" e existe um objeto chamado "Armario de servico", o
     * match é com esse — não com um sofá.
     */
    public List<Obj> objectsNamedIn(final String command, final Set<String> withinRooms) {
        final var said = significant(tokens(command));
        final var out = new ArrayList<Obj>();
        for (final var obj : this.objects) {
            if (!withinRooms.isEmpty() && !withinRooms.contains(obj.roomId())) continue;
            final var words = new LinkedHashSet<String>(tokens(obj.label()));
            for (final var kind : obj.kinds()) words.addAll(tokens(kind));
            if (significant(words).stream().anyMatch(w -> w.length() >= 4 && said.contains(w))) {
                out.add(obj);
            }
        }
        return out;
    }

    /**
     * Palavras funcionais do português, fora do vocabulário de identificação.
     *
     * <p>Não é enfeite: sem isto, "de" era token DISTINTIVO de
     * "SALA DE JANTAR | SALA DE ESTAR", e qualquer comando com "de" passava a
     * nomear a sala. O bug do sofá deixava de ser pego e, pior, "pinta a cama de
     * preto" era bloqueado como se a sala tivesse sido nomeada. Pago em teste
     * vermelho na primeira rodada desta fatia.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "de", "da", "do", "das", "dos", "em", "no", "na", "nos", "nas",
            "um", "uma", "uns", "umas", "com", "para", "por", "ao", "aos",
            "sem", "sob", "ate", "que", "the", "of");

    public static Set<String> significant(final Set<String> words) {
        final var out = new LinkedHashSet<String>();
        for (final var w : words) {
            if (!STOPWORDS.contains(w)) out.add(w);
        }
        return out;
    }

    /** Minúsculo, sem acento, quebrado em tokens de 2+ caracteres. */
    public static Set<String> tokens(final String text) {
        if (text == null || text.isBlank()) return Set.of();
        final var flat = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase();
        final var out = new LinkedHashSet<String>();
        for (final var piece : flat.split("[^a-z0-9]+")) {
            if (piece.length() >= 2) out.add(piece);
        }
        return out;
    }

    private static String str(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
