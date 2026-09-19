"""TOOL REGISTRY — o contrato entre o Agent Runtime e o sistema real.

Regra número um do Master Orchestrator: o modelo NÃO toca no sistema. Ele escolhe
uma tool declarada aqui e preenche argumentos tipados; quem executa é o Harness.
Não existe caminho de "shell livre" — se a capability não está nesta tabela, ela
não acontece.

Cada tool declara: nome, descrição, schema de entrada, risco, se é desfazível, o
que exige para funcionar, e o handler. `list_capabilities` publica a tabela
inteira, incluindo o que NÃO existe ainda (`unsupported`) — dizer "isso eu ainda
não sei fazer" é resposta; fingir um stub verde não é.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from . import gates as gates_mod
from .config import HarnessConfig
from .pipeline import Pipeline, PipelineUnavailable
from .scene import AmbiguousObject, SceneError, SceneStore, build_rooms

#: classificação de risco. Ver CLAUDE.md §risco.
LOW, MEDIUM, HIGH = "LOW", "MEDIUM", "HIGH"

#: direções em PLANTA (vista de topo), no frame do consenso/PDF em polegadas.
#: x cresce para a DIREITA; y cresce para CIMA na planta desenhada. Deliberado:
#: o modelo não deve raciocinar sobre eixo — ele diz "esquerda", o código resolve,
#: e o gate + o bbox devolvido confirmam o resultado.
_DIRECTIONS: dict[str, tuple[float, float, float]] = {
    "left": (-1, 0, 0), "esquerda": (-1, 0, 0), "west": (-1, 0, 0),
    "right": (1, 0, 0), "direita": (1, 0, 0), "east": (1, 0, 0),
    "up": (0, 1, 0), "north": (0, 1, 0), "cima": (0, 1, 0), "tras": (0, 1, 0),
    "back": (0, 1, 0), "forward": (0, -1, 0), "frente": (0, -1, 0),
    "down": (0, -1, 0), "south": (0, -1, 0), "baixo": (0, -1, 0),
    "raise": (0, 0, 1), "lower": (0, 0, -1),
}

MM_TO_IN = 1.0 / 25.4


@dataclass(frozen=True)
class ToolSpec:
    """A declaração de UMA capability. É isto que o modelo enxerga."""

    name: str
    description: str
    input_schema: dict
    handler: Callable[..., Any] = field(repr=False)
    risk: str = LOW
    undoable: bool = False
    requires: tuple[str, ...] = ()
    timeout_sec: int = 60
    mutates: bool = False

    def describe(self) -> dict:
        return {
            "name": self.name,
            "description": self.description,
            "inputSchema": self.input_schema,
            "risk": self.risk,
            "undoable": self.undoable,
            "requires": list(self.requires),
            "timeoutSec": self.timeout_sec,
            "mutates": self.mutates,
        }


#: capabilities que a missão nomeia e que AINDA NÃO existem no caminho real.
#: Publicadas de propósito: o agente precisa saber que não pode contar com elas.
UNSUPPORTED: dict[str, str] = {
    "rotate_object": "o pipeline gera peças eixo-alinhadas; girar exige regenerar pelo builder, não transladar",
    "scale_object": "dimensão vem da classe paramétrica de móvel (derive_spec), não de escala livre",
    "create_object": "criação é do cérebro de layout; a tool viria de furniture_class",
    "delete_object": "remoção muda o programa do cômodo — HIGH, e ainda sem caminho reversível",
    "set_material": "material/textura vive em style_spec + LAYOUT_TEX_MAP, ainda não exposto",
    "render": "render sobe o SketchUp em lote (não é serviço vivo); slice 5",
    "render_room": "idem render",
    "visual_review": "depende de render + juiz visual; slice 5",
    "create_contact_sheet": "depende de render; slice 5",
    "set_camera": "depende do SketchUp em lote; slice 5",
    "run_correction_loop": "tools/correction_loop opera sobre consensus/SKP, não sobre a cena editada ainda",
    "apply_to_skp": "materializar a cena editada num .skp exige rodar o SketchUp em lote; slice 2",
}


class Registry:
    """Monta e executa a tabela de capabilities."""

    def __init__(self, cfg: HarnessConfig):
        self.cfg = cfg
        self.pipe = Pipeline(cfg)
        self.store = SceneStore(cfg.scene_path, cfg.snapshot_dir)
        self._tools: dict[str, ToolSpec] = {}
        self._register_all()

    # -- API ----------------------------------------------------------------
    def describe(self) -> dict:
        return {
            "tools": [t.describe() for t in self._tools.values()],
            "unsupported": [{"name": k, "reason": v} for k, v in sorted(UNSUPPORTED.items())],
            "config": self.cfg.to_dict(),
        }

    def invoke(self, name: str, args: dict | None) -> dict:
        """Executa UMA tool com argumentos VALIDADOS. Erro vira resultado tipado."""
        spec = self._tools.get(name)
        if spec is None:
            reason = UNSUPPORTED.get(name)
            return _err("UNKNOWN_TOOL",
                        f"capability '{name}' não existe" + (f": {reason}" if reason else ""),
                        available=sorted(self._tools))
        args = dict(args or {})
        problem = _validate(spec.input_schema, args)
        if problem:
            return _err("INVALID_ARGUMENTS", f"{name}: {problem}",
                        schema=spec.input_schema)
        try:
            data = spec.handler(**args)
        except AmbiguousObject as exc:
            return _err("AMBIGUOUS", str(exc), candidates=exc.candidates)
        except SceneError as exc:
            return _err("SCENE_ERROR", str(exc))
        except PipelineUnavailable as exc:
            return _err("PIPELINE_UNAVAILABLE", str(exc), what=exc.what)
        except TypeError as exc:
            return _err("INVALID_ARGUMENTS", f"{name}: {exc}", schema=spec.input_schema)
        except Exception as exc:  # noqa: BLE001 — nunca derruba o host
            return _err("HANDLER_ERROR", f"{name}: {type(exc).__name__}: {exc}")
        return {"ok": True, "tool": name, "data": data}

    # -- registro -----------------------------------------------------------
    def _add(self, spec: ToolSpec) -> None:
        self._tools[spec.name] = spec

    def _register_all(self) -> None:
        obj = {"type": "string", "description": "id do objeto (ex. suite_01.escrivaninha) ou termo ('a escrivaninha')"}
        room = {"type": "string", "description": "id do cômodo (ex. r003). Omitido = todos"}

        self._add(ToolSpec(
            "get_system_status",
            "Estado real das dependências do pipeline (repo, gates, consenso) e da cena carregada.",
            {"type": "object", "properties": {}}, self._status))

        self._add(ToolSpec(
            "open_project",
            "Carrega o projeto. Sem cena salva, constrói a baseline rodando o cérebro "
            "determinístico do pipeline (demora ~1 min). `rebuild` força reconstruir e DESCARTA edições.",
            {"type": "object", "properties": {
                "rebuild": {"type": "boolean", "description": "reconstruir a baseline do zero"}}},
            self._open_project, risk=MEDIUM, requires=("pipeline",), timeout_sec=300, mutates=True))

        self._add(ToolSpec(
            "get_project_state",
            "Resumo do projeto: cômodos, quantos objetos, edições pendentes, travas, último snapshot limpo.",
            {"type": "object", "properties": {}}, self._project_state))

        self._add(ToolSpec(
            "list_rooms", "Lista os cômodos do projeto com id, nome e tipo.",
            {"type": "object", "properties": {}}, self._list_rooms))

        self._add(ToolSpec(
            "list_skp_artifacts",
            "Lista os arquivos .skp do projeto, do mais RECENTE para o mais antigo, "
            "com data e tamanho.",
            {"type": "object", "properties": {}}, self._list_skp,
            requires=("pipeline",)))

        self._add(ToolSpec(
            "open_skp_in_sketchup",
            "Abre um .skp no SketchUp. Sem argumento, abre o MAIS RECENTE do projeto — "
            "é o que responde 'abre a última planta'. Só abre para visualizar; "
            "não altera o arquivo.",
            {"type": "object", "properties": {
                "name": {"type": "string",
                         "description": "nome do arquivo; omitido = o mais recente"}}},
            self._open_skp, risk=MEDIUM, requires=("pipeline", "SketchUp"),
            timeout_sec=30))

        self._add(ToolSpec(
            "list_objects", "Lista os objetos (móveis) da cena, opcionalmente de um cômodo.",
            {"type": "object", "properties": {"room_id": room}}, self._list_objects))

        self._add(ToolSpec(
            "get_object", "Ficha de um objeto: cômodo, peças, bbox em metros, se está travado.",
            {"type": "object", "properties": {"object_id": obj}, "required": ["object_id"]},
            self._get_object))

        self._add(ToolSpec(
            "find_object",
            "Procura objetos por termo em linguagem natural ('a mesa', 'escrivaninha'). "
            "Use SEMPRE isto antes de mover: nunca invente um id.",
            {"type": "object", "properties": {
                "query": {"type": "string", "description": "o termo como o usuário falou"},
                "room_id": room}, "required": ["query"]},
            self._find_object))

        self._add(ToolSpec(
            "move_object",
            "Move um objeto na planta por DIREÇÃO e distância em milímetros. "
            "Direções: left/right (eixo X), forward/back (eixo Y), raise/lower (Z). "
            "Não calcule eixo nem coordenada — o Harness resolve e o gate confere.",
            {"type": "object", "properties": {
                "object_id": obj,
                "direction": {"type": "string", "enum": sorted(set(_DIRECTIONS)),
                              "description": "para onde mover, do ponto de vista da planta"},
                "distance_mm": {"type": "number", "description": "distância em milímetros (positiva)"},
                "reason": {"type": "string", "description": "por que, em uma linha"}},
             "required": ["object_id", "direction", "distance_mm"]},
            self._move_object, risk=LOW, undoable=True, requires=("scene",), mutates=True))

        self._add(ToolSpec(
            "run_gates",
            "Roda os gates determinísticos (circulação, colisão, sanidade geométrica) "
            "sobre a cena ATUAL de um cômodo. Esta é a única autoridade sobre 'ficou válido'.",
            {"type": "object", "properties": {"room_id": room}, "required": ["room_id"]},
            self._run_gates, requires=("pipeline", "scene"), timeout_sec=180))

        self._add(ToolSpec(
            "get_findings",
            "Findings pendentes do último `run_gates`, já roteados (AUTOFIX / VISION / FELIPE).",
            {"type": "object", "properties": {"room_id": room}, "required": ["room_id"]},
            self._get_findings, requires=("pipeline", "scene"), timeout_sec=180))

        self._add(ToolSpec(
            "undo", "Desfaz a última alteração da cena.",
            {"type": "object", "properties": {}}, self._undo,
            risk=LOW, undoable=True, requires=("scene",), mutates=True))

        self._add(ToolSpec(
            "redo", "Refaz a alteração desfeita por último.",
            {"type": "object", "properties": {}}, self._redo,
            risk=LOW, undoable=True, requires=("scene",), mutates=True))

        self._add(ToolSpec(
            "list_history", "Histórico de alterações aplicadas na cena, em ordem.",
            {"type": "object", "properties": {}}, self._history))

        self._add(ToolSpec(
            "save_snapshot", "Salva um ponto de retorno da cena inteira.",
            {"type": "object", "properties": {
                "label": {"type": "string", "description": "rótulo curto"},
                "clean": {"type": "boolean", "description": "marcar como a última versão CLEAN"}}},
            self._save_snapshot, risk=LOW, requires=("scene",), mutates=True))

        self._add(ToolSpec(
            "list_snapshots", "Lista os snapshots salvos.",
            {"type": "object", "properties": {}}, self._list_snapshots))

        self._add(ToolSpec(
            "restore_snapshot", "Volta a cena para um snapshot salvo.",
            {"type": "object", "properties": {
                "snapshot_id": {"type": "string"}}, "required": ["snapshot_id"]},
            self._restore, risk=MEDIUM, requires=("scene",), mutates=True))

        self._add(ToolSpec(
            "restore_last_clean", "Volta para a última versão marcada como CLEAN.",
            {"type": "object", "properties": {}}, self._restore_clean,
            risk=MEDIUM, requires=("scene",), mutates=True))

        self._add(ToolSpec(
            "lock_object",
            "Trava um objeto: nenhuma alteração pode movê-lo. Use quando o usuário "
            "disser 'não mexa na cama'.",
            {"type": "object", "properties": {"object_id": obj}, "required": ["object_id"]},
            self._lock, requires=("scene",), mutates=True))

        self._add(ToolSpec(
            "unlock_object", "Destrava um objeto.",
            {"type": "object", "properties": {"object_id": obj}, "required": ["object_id"]},
            self._unlock, requires=("scene",), mutates=True))

        self._add(ToolSpec(
            "list_locked_objects", "Objetos que não podem ser movidos.",
            {"type": "object", "properties": {}}, self._list_locked))

    # -- handlers -----------------------------------------------------------
    def _status(self) -> dict:
        avail = self.pipe.availability()
        scene = {"loaded": self.store.exists(), "path": str(self.cfg.scene_path)}
        if self.store.exists():
            try:
                doc = self.store.load()
                scene.update({"project": doc["project"], "builtAt": doc["builtAt"],
                              "rooms": len(doc["rooms"]), "parts": len(doc["baseline"]),
                              "edits": len(doc["edits"]), "locked": len(doc.get("locked", []))})
            except SceneError as exc:
                scene["error"] = str(exc)
        return {"pipeline": avail, "scene": scene, "project": self.cfg.project}

    def _open_project(self, rebuild: bool = False) -> dict:
        if self.store.exists() and not rebuild:
            doc = self.store.load()
            return {"built": False, "project": doc["project"], "rooms": len(doc["rooms"]),
                    "objects": len(self.store.objects()), "edits": len(doc["edits"])}
        con = self.pipe.consensus()
        boxes, summary = self.pipe.collect_boxes(con)
        rooms = build_rooms(lambda: self.pipe.classify_rooms(con))
        doc = self.store.build(
            project=self.cfg.project, rooms=rooms, boxes=boxes,
            source={"consensus": str(self.cfg.consensus_path), "ptToM": self.cfg.pt_to_m,
                    "builder": "tools.furnish_apartment.collect_boxes",
                    "summary": [list(map(_plain, row)) for row in summary]})
        return {"built": True, "project": doc["project"], "rooms": len(rooms),
                "parts": len(boxes), "objects": len(self.store.objects())}

    def _project_state(self) -> dict:
        doc = self.store.load()
        objs = self.store.objects()
        return {
            "project": doc["project"], "builtAt": doc["builtAt"],
            "rooms": doc["rooms"], "objectCount": len(objs), "partCount": len(doc["baseline"]),
            "edits": doc["edits"], "locked": doc.get("locked", []),
            "lastCleanSnapshot": doc.get("lastCleanSnapshot"),
            "undoAvailable": bool(doc["edits"]),
        }

    def _list_rooms(self) -> dict:
        doc = self.store.load()
        counts: dict[str, int] = {}
        for o in self.store.objects():
            counts[o.room_id] = counts.get(o.room_id, 0) + 1
        return {"rooms": [{**r, "objectCount": counts.get(r["id"], 0)} for r in doc["rooms"]]}

    def _list_skp(self) -> dict:
        items = self.pipe.skp_artifacts()
        return {"count": len(items), "newest": items[0] if items else None,
                "artifacts": items[:20]}

    def _open_skp(self, name: str | None = None) -> dict:
        items = self.pipe.skp_artifacts()
        if not items:
            raise SceneError(
                f"nenhum .skp encontrado em artifacts/{self.cfg.project}/ "
                "— o projeto ainda não foi materializado")
        if name:
            wanted = str(name).strip().lower()
            matches = [i for i in items if wanted in i["name"].lower()]
            if not matches:
                raise SceneError(
                    f"nenhum .skp com '{name}'. Disponíveis: "
                    + ", ".join(i["name"] for i in items[:6]))
            chosen = matches[0]
        else:
            chosen = items[0]
        result = self.pipe.open_in_sketchup(chosen["path"])
        return {"opened": chosen["name"], "path": chosen["path"],
                "modified": chosen["modified"], "sizeMb": chosen["sizeMb"],
                "wasNewest": chosen is items[0], "exe": result["exe"]}

    def _list_objects(self, room_id: str | None = None) -> dict:
        objs = self.store.objects(room_id)
        return {"roomId": room_id, "count": len(objs), "objects": [o.to_dict() for o in objs]}

    def _get_object(self, object_id: str) -> dict:
        return self.store.resolve_one(object_id).to_dict()

    def _find_object(self, query: str, room_id: str | None = None) -> dict:
        hits = self.store.find(query, room_id=room_id)
        if not hits and room_id:
            hits = self.store.find(query)
        return {"query": query, "count": len(hits),
                "matches": [o.to_dict() for o in hits],
                "unique": hits[0].id if len(hits) == 1 else None}

    def _move_object(self, object_id: str, direction: str, distance_mm: float,
                     reason: str = "") -> dict:
        vec = _DIRECTIONS.get(str(direction).strip().lower())
        if vec is None:
            raise SceneError(
                f"direção '{direction}' desconhecida; use uma de: {', '.join(sorted(set(_DIRECTIONS)))}")
        if float(distance_mm) <= 0:
            raise SceneError("distance_mm precisa ser positiva — a direção diz o sentido")
        obj = self.store.resolve_one(object_id)
        d_in = float(distance_mm) * MM_TO_IN
        before = obj.bbox_in
        edit = self.store.translate(obj.id, vec[0] * d_in, vec[1] * d_in, vec[2] * d_in,
                                    reason=reason)
        after = self.store.object(obj.id)
        return {"objectId": obj.id, "label": obj.label, "room": obj.room, "roomId": obj.room_id,
                "direction": direction, "distanceMm": float(distance_mm),
                "partsMoved": obj.part_count, "edit": edit,
                "bboxBefore": before, "bboxAfter": after.bbox_in,
                "centerBeforeM": _center_m(before), "centerAfterM": _center_m(after.bbox_in)}

    def _run_gates(self, room_id: str) -> dict:
        con = self.pipe.consensus()
        boxes = self.store.boxes()
        room_boxes = self.store.boxes_in_room(room_id)
        if not room_boxes:
            raise SceneError(f"cômodo '{room_id}' não tem mobília na cena (ou não existe)")
        return gates_mod.run_room_gates(self.pipe, con, boxes, room_id, room_boxes)

    def _get_findings(self, room_id: str) -> dict:
        report = self._run_gates(room_id)
        by_route: dict[str, list[dict]] = {}
        for f in report["findings"]:
            by_route.setdefault(f["route"], []).append(f)
        return {"roomId": room_id, "overall": report["overall"],
                "findings": report["findings"], "byRoute": by_route}

    def _undo(self) -> dict:
        edit = self.store.undo()
        if edit is None:
            return {"undone": False, "detail": "nada para desfazer"}
        return {"undone": True, "edit": edit, "remaining": len(self.store.history())}

    def _redo(self) -> dict:
        edit = self.store.redo()
        if edit is None:
            return {"redone": False, "detail": "nada para refazer"}
        return {"redone": True, "edit": edit}

    def _history(self) -> dict:
        h = self.store.history()
        return {"count": len(h), "edits": h}

    def _save_snapshot(self, label: str = "", clean: bool = False) -> dict:
        snap = self.store.snapshot(label)
        if clean:
            self.store.mark_clean(snap["id"])
            snap["markedClean"] = True
        return snap

    def _list_snapshots(self) -> dict:
        return {"snapshots": self.store.list_snapshots(),
                "lastClean": self.store.last_clean()}

    def _restore(self, snapshot_id: str) -> dict:
        return self.store.restore(snapshot_id)

    def _restore_clean(self) -> dict:
        sid = self.store.last_clean()
        if not sid:
            raise SceneError("nenhuma versão CLEAN foi marcada ainda "
                             "(use save_snapshot com clean=true depois de um gate PASS)")
        return self.store.restore(sid)

    def _lock(self, object_id: str) -> dict:
        obj = self.store.resolve_one(object_id)
        return {"locked": self.store.lock(obj.id), "objectId": obj.id, "label": obj.label}

    def _unlock(self, object_id: str) -> dict:
        obj = self.store.resolve_one(object_id)
        return {"locked": self.store.unlock(obj.id), "objectId": obj.id}

    def _list_locked(self) -> dict:
        ids = self.store.locked()
        return {"count": len(ids), "locked": ids}


# -- utilidades --------------------------------------------------------------
def _err(code: str, message: str, **extra) -> dict:
    return {"ok": False, "error": {"code": code, "message": message, **extra}}


def _center_m(bb: dict | None) -> list | None:
    if not bb:
        return None
    return [round((bb["x0"] + bb["x1"]) / 2 * 0.0254, 3),
            round((bb["y0"] + bb["y1"]) / 2 * 0.0254, 3)]


def _plain(v):
    """Torna a linha de summary serializável sem perder informação."""
    return v if isinstance(v, (str, int, float, bool, type(None))) else str(v)


_TYPES = {"string": str, "number": (int, float), "boolean": bool,
          "object": dict, "array": list, "integer": int}


def _validate(schema: dict, args: dict) -> str | None:
    """Validação de schema suficiente para o contrato — e ESTRITA no que importa.

    Recusa argumento desconhecido de propósito: modelo que alucina um parâmetro
    precisa ver o erro, não ter o parâmetro silenciosamente ignorado.
    """
    props = schema.get("properties", {})
    for req in schema.get("required", []):
        if req not in args or args[req] is None:
            return f"falta o argumento obrigatório '{req}'"
    for key, value in args.items():
        if key not in props:
            return (f"argumento '{key}' não existe nesta tool "
                    f"(aceita: {', '.join(props) or 'nenhum'})")
        expected = props[key].get("type")
        py = _TYPES.get(expected)
        if py and value is not None and not isinstance(value, py):
            if expected == "number" and isinstance(value, bool):
                return f"'{key}' deveria ser {expected}"
            if not (expected == "number" and isinstance(value, (int, float))):
                return f"'{key}' deveria ser {expected}, veio {type(value).__name__}"
        enum = props[key].get("enum")
        if enum and value is not None and value not in enum:
            return f"'{key}' precisa ser um de: {', '.join(map(str, enum))}"
    return None
