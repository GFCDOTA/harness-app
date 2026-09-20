"""SceneStore — o estado da planta que o Harness opera, com história.

Por que isto existe: hoje o pipeline RECOMPUTA a mobília a cada execução
(`collect_boxes`) e entrega a lista de caixas direto pro Ruby por variável de
ambiente. Nada fica guardado. Sem documento persistido não existe "move mais
10 cm", não existe "desfaz", e não existe gate rodando sobre a edição — cada
comando recomeçaria do zero.

O documento tem duas partes, e a separação é deliberada:

* **baseline** — o que o cérebro determinístico produziu. Nunca é reescrito.
* **edits**    — o que o agente fez por cima, em ordem, cada um invertível.

O estado corrente é sempre `baseline + edits aplicadas`. Isso dá reprodutibilidade
(dá pra reconstruir a cena do zero), `undo` exato (translação é exatamente
invertível) e auditoria (o log diz quem mexeu no quê e quando).

Unidade: POLEGADAS, como o pipeline inteiro. A conversão para metros só acontece
na borda de apresentação — nunca no armazenamento.
"""
from __future__ import annotations

import json
import re
import time
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

SCHEMA_VERSION = 2
IN_TO_M = 0.0254
M_TO_IN = 1.0 / IN_TO_M

#: chaves do box que carregam posição e precisam andar junto numa translação.
_X_KEYS = ("x0", "x1")
_Y_KEYS = ("y0", "y1")
_Z_KEYS = ("z0_in",)


def slug(text: str) -> str:
    """`SALA DE JANTAR | SALA DE ESTAR` -> `sala_de_jantar_sala_de_estar`."""
    t = unicodedata.normalize("NFKD", str(text or ""))
    t = "".join(c for c in t if not unicodedata.combining(c))
    t = t.lower()
    t = re.sub(r"[^a-z0-9]+", "_", t)
    return t.strip("_") or "sem_nome"


#: palavras que o Felipe fala e que não identificam nada.
#: Sem tirar isto, "a escrivaninha" deixava de casar EXATAMENTE com o módulo
#: `Escrivaninha` e caía na busca por substring — onde `cadeira_escrivaninha`
#: também casa. Resultado real: o agente pedia desambiguação entre a mesa e a
#: cadeira dela, numa frase que não tinha ambiguidade nenhuma.
_STOPWORDS = frozenset({
    "a", "o", "as", "os", "um", "uma", "uns", "umas",
    "da", "do", "das", "dos", "de", "na", "no", "nas", "nos", "em",
    "the", "this", "that",
})


def normalize_query(text: str) -> str:
    """Normaliza o termo do usuário: sem acento, sem artigo, sem preposição."""
    tokens = [t for t in slug(text).split("_") if t and t not in _STOPWORDS]
    return "_".join(tokens)


def translate_box(box: dict, dx: float, dy: float, dz: float) -> dict:
    """Move UM box. Devolve um novo dict — o original não é tocado.

    Mexe em tudo que é posição: extremos, `corners` (2D) e `profile_world` (3D).
    `extrude_vec` é DIREÇÃO, não posição, e por isso fica igual — mover uma peça
    não muda para que lado ela foi extrudada.
    """
    out = dict(box)
    for k in _X_KEYS:
        if isinstance(out.get(k), (int, float)):
            out[k] = round(out[k] + dx, 6)
    for k in _Y_KEYS:
        if isinstance(out.get(k), (int, float)):
            out[k] = round(out[k] + dy, 6)
    for k in _Z_KEYS:
        if isinstance(out.get(k), (int, float)):
            out[k] = round(out[k] + dz, 6)

    corners = out.get("corners")
    if isinstance(corners, list):
        out["corners"] = [
            [round(p[0] + dx, 6), round(p[1] + dy, 6)] + list(p[2:])
            if isinstance(p, list) and len(p) >= 2 else p
            for p in corners
        ]
    prof = out.get("profile_world")
    if isinstance(prof, list):
        moved = []
        for p in prof:
            if isinstance(p, list) and len(p) >= 3:
                moved.append([round(p[0] + dx, 6), round(p[1] + dy, 6), round(p[2] + dz, 6)])
            elif isinstance(p, list) and len(p) == 2:
                moved.append([round(p[0] + dx, 6), round(p[1] + dy, 6)])
            else:
                moved.append(p)
        out["profile_world"] = moved
    return out


def bbox_of(boxes: Iterable[dict]) -> dict | None:
    """Envelope em polegadas do conjunto. `None` se nada tiver extensão."""
    xs0, ys0, xs1, ys1, zs0, zs1 = [], [], [], [], [], []
    for b in boxes:
        if isinstance(b.get("x0"), (int, float)):
            xs0.append(b["x0"]); xs1.append(b.get("x1", b["x0"]))
        if isinstance(b.get("y0"), (int, float)):
            ys0.append(b["y0"]); ys1.append(b.get("y1", b["y0"]))
        z0 = b.get("z0_in")
        if isinstance(z0, (int, float)):
            zs0.append(z0)
            h = b.get("h_in")
            zs1.append(z0 + h if isinstance(h, (int, float)) else z0)
    if not xs0 or not ys0:
        return None
    return {
        "x0": round(min(xs0), 3), "y0": round(min(ys0), 3),
        "x1": round(max(xs1), 3), "y1": round(max(ys1), 3),
        "z0": round(min(zs0), 3) if zs0 else None,
        "z1": round(max(zs1), 3) if zs1 else None,
    }


def _bbox_m(bb: dict | None) -> dict | None:
    if not bb:
        return None
    return {
        "widthM": round((bb["x1"] - bb["x0"]) * IN_TO_M, 3),
        "depthM": round((bb["y1"] - bb["y0"]) * IN_TO_M, 3),
        "heightM": round(((bb["z1"] or 0) - (bb["z0"] or 0)) * IN_TO_M, 3)
        if bb.get("z1") is not None and bb.get("z0") is not None else None,
        "centerM": [round((bb["x0"] + bb["x1"]) / 2 * IN_TO_M, 3),
                    round((bb["y0"] + bb["y1"]) / 2 * IN_TO_M, 3)],
    }


@dataclass(frozen=True)
class SceneObject:
    """Uma peça de mobília como o Felipe fala dela: 'a escrivaninha da suíte 01'.

    Um objeto agrupa VÁRIOS boxes (a cama tem colchão, base, cabeceira...). O
    agrupamento é por `module`, que é justamente o campo com que o pipeline
    nomeia unidades físicas — não é um apelido inventado aqui.
    """

    id: str
    room_id: str
    room: str
    module: str
    label: str
    kinds: tuple[str, ...]
    part_count: int
    locked: bool
    bbox_in: dict | None

    def to_dict(self) -> dict:
        return {
            "id": self.id, "roomId": self.room_id, "room": self.room,
            "module": self.module, "label": self.label, "kinds": list(self.kinds),
            "partCount": self.part_count, "locked": self.locked,
            "bboxIn": self.bbox_in, "bboxM": _bbox_m(self.bbox_in),
        }


class SceneError(RuntimeError):
    """Erro de operação sobre a cena, com mensagem que serve pro usuário."""


class AmbiguousObject(SceneError):
    """Mais de um objeto casa com o termo — quem decide não é o modelo."""

    def __init__(self, query: str, candidates: list[dict]):
        names = ", ".join(f"{c['id']} ({c['room']})" for c in candidates[:6])
        super().__init__(f"'{query}' casa com mais de um objeto: {names}")
        self.query = query
        self.candidates = candidates


class SceneStore:
    """Dono do documento de cena: carrega, edita, versiona, persiste."""

    def __init__(self, path: Path, snapshot_dir: Path):
        self.path = Path(path)
        self.snapshot_dir = Path(snapshot_dir)
        self._doc: dict | None = None

    # -- ciclo de vida ------------------------------------------------------
    def exists(self) -> bool:
        return self.path.exists()

    def load(self) -> dict:
        if self._doc is not None:
            return self._doc
        if not self.path.exists():
            raise SceneError(
                f"nenhuma cena carregada para este projeto ({self.path.name}). "
                "Rode `open_project` para construí-la a partir do pipeline.")
        doc = json.loads(self.path.read_text("utf-8"))
        if doc.get("schemaVersion") != SCHEMA_VERSION:
            raise SceneError(
                f"cena em schema {doc.get('schemaVersion')}, host espera {SCHEMA_VERSION}")
        self._doc = doc
        return doc

    def build(self, *, project: str, rooms: list[dict], boxes: list[dict],
              source: dict) -> dict:
        """Cria o documento a partir do baseline determinístico do pipeline."""
        doc = {
            "schemaVersion": SCHEMA_VERSION,
            "project": project,
            "builtAt": _now(),
            "source": source,
            "rooms": rooms,
            "baseline": boxes,
            "edits": [],
            "locked": [],
            "lastCleanSnapshot": None,
        }
        self._doc = doc
        self.save()
        return doc

    def save(self) -> None:
        doc = self.load() if self._doc is None else self._doc
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(doc, ensure_ascii=False), "utf-8")
        tmp.replace(self.path)

    # -- estado corrente ----------------------------------------------------
    def boxes(self) -> list[dict]:
        """baseline + todas as edições aplicadas, na ordem."""
        doc = self.load()
        out = [dict(b) for b in doc["baseline"]]
        for edit in doc["edits"]:
            out = self._apply(out, edit)
        return out

    def boxes_of(self, object_id: str) -> list[dict]:
        return [b for b in self.boxes() if self._object_id(b) == object_id]

    def boxes_in_room(self, room_id: str) -> list[dict]:
        names = {r["name"] for r in self.load()["rooms"] if r["id"] == room_id}
        return [b for b in self.boxes() if b.get("room") in names]

    def _apply(self, boxes: list[dict], edit: dict) -> list[dict]:
        if edit["op"] != "translate":
            raise SceneError(f"operação desconhecida no log: {edit['op']}")
        oid = edit["objectId"]
        dx, dy, dz = edit["dxIn"], edit["dyIn"], edit["dzIn"]
        return [translate_box(b, dx, dy, dz) if self._object_id(b) == oid else b
                for b in boxes]

    # -- identidade ---------------------------------------------------------
    def _object_id(self, box: dict) -> str:
        return f"{slug(box.get('room'))}.{slug(box.get('module') or box.get('kind'))}"

    def objects(self, room_id: str | None = None) -> list[SceneObject]:
        doc = self.load()
        by_name = {r["name"]: r for r in doc["rooms"]}
        locked = set(doc.get("locked", []))
        grouped: dict[str, list[dict]] = {}
        for b in self.boxes():
            grouped.setdefault(self._object_id(b), []).append(b)

        out: list[SceneObject] = []
        for oid, parts in grouped.items():
            room_name = parts[0].get("room")
            room = by_name.get(room_name, {})
            if room_id and room.get("id") != room_id:
                continue
            module = parts[0].get("module") or parts[0].get("kind") or "?"
            out.append(SceneObject(
                id=oid,
                room_id=room.get("id", ""),
                room=room_name or "",
                module=str(module),
                # O nome que a pessoa reconhece é o do MÓDULO ("Escrivaninha"), não
                # o rótulo da primeira peça — que é interno ("top", "corpo", "saia")
                # e produzia frases como "top movida 300 mm" na tela.
                label=str(module),
                kinds=tuple(sorted({str(p.get("kind")) for p in parts if p.get("kind")})),
                part_count=len(parts),
                locked=oid in locked,
                bbox_in=bbox_of(parts),
            ))
        out.sort(key=lambda o: (o.room, o.module))
        return out

    def object(self, object_id: str) -> SceneObject:
        for o in self.objects():
            if o.id == object_id:
                return o
        raise SceneError(f"objeto '{object_id}' não existe na cena")

    def find(self, query: str, *, room_id: str | None = None) -> list[SceneObject]:
        """Busca por termo em id/módulo/rótulo/kind. NUNCA devolve um chute.

        Casa primeiro exato, depois substring, depois token — e só desce um
        nível quando o de cima não achou nada. Assim 'cama' não traz 'Criado-mudo'
        junto por acaso.
        """
        q = normalize_query(query)
        pool = self.objects(room_id)
        if not q:
            return []

        def fields(o: SceneObject) -> list[str]:
            return [o.id, slug(o.module), slug(o.label)] + [slug(k) for k in o.kinds]

        exact = [o for o in pool if q in fields(o)]
        if exact:
            return exact
        # termo do usuário contido no campo, ou campo contido no termo
        # ('escrivaninha' casa 'Escrivaninha'; 'mesa de jantar' casa 'Mesa de jantar')
        partial = [o for o in pool if any(q in f or f in q for f in fields(o))]
        if partial:
            return partial
        tokens = [t for t in q.split("_") if len(t) > 2]
        return [o for o in pool
                if any(any(t in f for f in fields(o)) for t in tokens)]

    def resolve_one(self, query: str, *, room_id: str | None = None,
                    last_referenced: str | None = None) -> SceneObject:
        """Resolve para UM objeto ou levanta `AmbiguousObject`.

        Ordem: id exato > busca no cômodo ativo > busca global. Se ainda houver
        empate, o objeto referenciado por último ganha — é o que torna "move mais
        10 cm" possível sem o modelo adivinhar id.
        """
        for o in self.objects():
            if o.id == query:
                return o
        hits = self.find(query, room_id=room_id) if room_id else []
        if not hits:
            hits = self.find(query)
        if not hits:
            raise SceneError(f"não encontrei nenhum objeto que case com '{query}'")
        if len(hits) == 1:
            return hits[0]
        if last_referenced:
            for o in hits:
                if o.id == last_referenced:
                    return o
        raise AmbiguousObject(query, [o.to_dict() for o in hits])

    # -- edição -------------------------------------------------------------
    def translate(self, object_id: str, dx_in: float, dy_in: float, dz_in: float,
                  *, reason: str = "") -> dict:
        doc = self.load()
        obj = self.object(object_id)
        if obj.locked:
            raise SceneError(
                f"'{object_id}' está travado (o Felipe pediu para não mexer). "
                "Use `unlock_object` antes.")
        edit = {
            "op": "translate", "objectId": object_id,
            "dxIn": round(float(dx_in), 6), "dyIn": round(float(dy_in), 6),
            "dzIn": round(float(dz_in), 6),
            "at": _now(), "reason": reason,
        }
        doc["edits"].append(edit)
        self.save()
        return edit

    def undo(self) -> dict | None:
        doc = self.load()
        if not doc["edits"]:
            return None
        edit = doc["edits"].pop()
        doc.setdefault("undone", []).append(edit)
        self.save()
        return edit

    def redo(self) -> dict | None:
        doc = self.load()
        stack = doc.get("undone") or []
        if not stack:
            return None
        edit = stack.pop()
        doc["edits"].append(edit)
        self.save()
        return edit

    def history(self) -> list[dict]:
        return list(self.load()["edits"])

    # -- materialização -----------------------------------------------------
    def mark_materialized(self, path: str) -> dict:
        """Anota que a cena, COMO ESTÁ AGORA, virou um `.skp`.

        Guarda a contagem de edições no momento, não um booleano: é isso que
        permite responder "quantas edições ainda não foram para o arquivo" depois
        de mais uma edição, sem recomputar nada.
        """
        doc = self.load()
        mark = {"at": _now(), "path": path, "editCount": len(doc["edits"])}
        doc["materialized"] = mark
        self.save()
        return dict(mark)

    def last_materialized(self) -> dict | None:
        return self.load().get("materialized")

    def unmaterialized_edits(self) -> int:
        """Quantas edições ainda não chegaram no `.skp`.

        Sem materialização nenhuma, toda edição está pendente — que é a verdade
        do sistema antes desta fatia existir.
        """
        doc = self.load()
        mark = doc.get("materialized")
        done = int(mark.get("editCount", 0)) if mark else 0
        return max(len(doc["edits"]) - done, 0)

    # -- travas -------------------------------------------------------------
    def lock(self, object_id: str) -> list[str]:
        doc = self.load()
        self.object(object_id)
        locked = set(doc.get("locked", []))
        locked.add(object_id)
        doc["locked"] = sorted(locked)
        self.save()
        return doc["locked"]

    def unlock(self, object_id: str) -> list[str]:
        doc = self.load()
        locked = set(doc.get("locked", []))
        locked.discard(object_id)
        doc["locked"] = sorted(locked)
        self.save()
        return doc["locked"]

    def locked(self) -> list[str]:
        return list(self.load().get("locked", []))

    # -- snapshots ----------------------------------------------------------
    def snapshot(self, label: str = "") -> dict:
        doc = self.load()
        self.snapshot_dir.mkdir(parents=True, exist_ok=True)
        sid = f"snap_{time.strftime('%Y%m%dT%H%M%S')}_{len(doc['edits'])}"
        path = self.snapshot_dir / f"{sid}.json"
        path.write_text(json.dumps(doc, ensure_ascii=False), "utf-8")
        return {"id": sid, "path": str(path), "label": label,
                "editCount": len(doc["edits"]), "at": _now()}

    def list_snapshots(self) -> list[dict]:
        if not self.snapshot_dir.exists():
            return []
        out = []
        for p in sorted(self.snapshot_dir.glob("snap_*.json")):
            out.append({"id": p.stem, "path": str(p),
                        "sizeBytes": p.stat().st_size,
                        "at": time.strftime("%Y-%m-%dT%H:%M:%S",
                                            time.localtime(p.stat().st_mtime))})
        return out

    def restore(self, snapshot_id: str) -> dict:
        path = self.snapshot_dir / f"{snapshot_id}.json"
        if not path.exists():
            raise SceneError(f"snapshot '{snapshot_id}' não existe")
        doc = json.loads(path.read_text("utf-8"))
        self._doc = doc
        self.save()
        return {"restored": snapshot_id, "editCount": len(doc["edits"])}

    def mark_clean(self, snapshot_id: str) -> None:
        doc = self.load()
        doc["lastCleanSnapshot"] = snapshot_id
        self.save()

    def last_clean(self) -> str | None:
        return self.load().get("lastCleanSnapshot")


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def build_rooms(classify: Callable[[], list[dict]]) -> list[dict]:
    """Normaliza a saída de `classify_rooms` para o documento."""
    return [{"id": r["id"], "name": r["name"], "type": r.get("room_type")}
            for r in classify()]
