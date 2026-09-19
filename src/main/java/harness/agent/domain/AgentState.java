package harness.agent.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * O contexto operacional entre comandos. É isto que torna possível "move mais
 * 10 cm", "desfaz" e "agora faz o mesmo na outra mesa".
 *
 * <p>Deliberadamente NÃO é a memória textual do modelo (missão §10). O modelo
 * esquece, alucina e reordena; este objeto é fato. O que o runtime manda para o
 * planner é um retrato DESTE estado, não a transcrição da conversa.
 *
 * <p>Mutável e de dono único: vive na raiz de composição da UI, numa thread só.
 */
public final class AgentState {

    private String activeProject;
    private String activeRoom;
    private String lastReferencedObject;
    private String lastAction;
    private String lastCleanSnapshot;
    private final Set<String> lockedObjects = new LinkedHashSet<>();
    private final List<Turn> conversation = new ArrayList<>();
    private int pendingEdits;

    /** Uma rodada de conversa operacional: o que foi pedido e o que aconteceu. */
    public record Turn(String command, String status, String summary) {
    }

    public AgentState(final String project) {
        this.activeProject = project;
    }

    public String activeProject() {
        return this.activeProject;
    }

    public void activeProject(final String project) {
        this.activeProject = project;
    }

    public Optional<String> activeRoom() {
        return Optional.ofNullable(this.activeRoom);
    }

    public void activeRoom(final String roomId) {
        this.activeRoom = roomId;
    }

    public Optional<String> lastReferencedObject() {
        return Optional.ofNullable(this.lastReferencedObject);
    }

    public void lastReferencedObject(final String objectId) {
        this.lastReferencedObject = objectId;
    }

    public Optional<String> lastAction() {
        return Optional.ofNullable(this.lastAction);
    }

    public void lastAction(final String action) {
        this.lastAction = action;
    }

    public Optional<String> lastCleanSnapshot() {
        return Optional.ofNullable(this.lastCleanSnapshot);
    }

    public void lastCleanSnapshot(final String snapshotId) {
        this.lastCleanSnapshot = snapshotId;
    }

    public Set<String> lockedObjects() {
        return Set.copyOf(this.lockedObjects);
    }

    public void lock(final String objectId) {
        this.lockedObjects.add(objectId);
    }

    public void unlock(final String objectId) {
        this.lockedObjects.remove(objectId);
    }

    public void replaceLocks(final List<String> ids) {
        this.lockedObjects.clear();
        if (ids != null) this.lockedObjects.addAll(ids);
    }

    public int pendingEdits() {
        return this.pendingEdits;
    }

    public void pendingEdits(final int count) {
        this.pendingEdits = Math.max(0, count);
    }

    public boolean undoAvailable() {
        return this.pendingEdits > 0;
    }

    public void record(final String command, final String status, final String summary) {
        this.conversation.add(new Turn(command, status, summary));
    }

    /**
     * As últimas rodadas, mais novas por último.
     *
     * <p>Janela curta de propósito: o modelo é local e prompt gigante custa caro
     * (missão §41). O que precisa sobreviver a muitos turnos mora nos campos acima,
     * não no histórico.
     */
    public List<Turn> recentTurns(final int max) {
        final var from = Math.max(0, this.conversation.size() - max);
        return List.copyOf(this.conversation.subList(from, this.conversation.size()));
    }

    /**
     * Aprende com o que a tool REALMENTE devolveu.
     *
     * <p>O estado segue o sistema, nunca a narrativa do modelo: se ele disser que
     * moveu a mesa e a tool responder ambíguo, nada aqui muda.
     */
    public void observe(final ToolResult result) {
        if (!result.ok()) return;
        final var objectId = result.data().get("objectId");
        if (objectId instanceof String id && !id.isBlank()) {
            this.lastReferencedObject = id;
            final var roomId = result.data().get("roomId");
            if (roomId instanceof String room && !room.isBlank()) this.activeRoom = room;
        }
        final var unique = result.data().get("unique");
        if (unique instanceof String id && !id.isBlank()) this.lastReferencedObject = id;
        final var locked = result.data().get("locked");
        if (locked instanceof List<?> list) {
            replaceLocks(list.stream().filter(String.class::isInstance)
                    .map(String.class::cast).toList());
        }
        switch (result.tool()) {
            case "move_object" -> {
                this.lastAction = "move_object";
                this.pendingEdits++;
            }
            case "undo" -> {
                if (Boolean.TRUE.equals(result.data().get("undone"))) {
                    this.lastAction = "undo";
                    this.pendingEdits = Math.max(0, this.pendingEdits - 1);
                }
            }
            case "redo" -> {
                if (Boolean.TRUE.equals(result.data().get("redone"))) this.pendingEdits++;
            }
            case "save_snapshot" -> {
                if (Boolean.TRUE.equals(result.data().get("markedClean"))
                        && result.data().get("id") instanceof String id) {
                    this.lastCleanSnapshot = id;
                }
            }
            case "restore_snapshot", "restore_last_clean" -> {
                final var count = result.data().get("editCount");
                if (count instanceof Number number) this.pendingEdits = number.intValue();
                this.lastAction = "restore";
            }
            case "get_project_state", "open_project" -> {
                final var edits = result.data().get("edits");
                if (edits instanceof Number number) this.pendingEdits = number.intValue();
                else if (edits instanceof List<?> list) this.pendingEdits = list.size();
            }
            default -> { /* leitura pura não move o estado */ }
        }
    }
}
