"""ADAPTER para o pipeline real (`sketchup-mcp`).

É o ÚNICO lugar do capability host que importa o repo do pipeline. Todo import é
tardio e guardado: repo ausente, branch sem o módulo ou dependência faltando vira
**indisponibilidade declarada**, nunca um stub que finge ter rodado.

A regra que isto protege (CLAUDE.md §honestidade): uma capability só reporta
sucesso quando o código real rodou. `PipelineUnavailable` é uma resposta legítima;
um PASS inventado não é.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

from .config import HarnessConfig


class PipelineUnavailable(RuntimeError):
    """O pipeline não pôde ser usado — com o motivo REAL, não um genérico."""

    def __init__(self, what: str, why: str):
        super().__init__(f"{what} indisponível: {why}")
        self.what = what
        self.why = why


class Pipeline:
    """Fachada fina sobre `sketchup-mcp`. Sem lógica de domínio própria."""

    def __init__(self, cfg: HarnessConfig):
        self.cfg = cfg
        self._prepared = False

    # -- preparo ------------------------------------------------------------
    def prepare(self) -> None:
        """Põe o repo no `sys.path` e congela PT_TO_M ANTES de qualquer import.

        `core.scale` congela a escala no PRIMEIRO import; setar depois não corrige
        (gotcha pago: a mobília flutua 1.36x fora do shell). Por isso o env vem
        antes de tudo, e uma vez só.
        """
        if self._prepared:
            return
        repo = self.cfg.pipeline_repo
        if not repo.exists():
            raise PipelineUnavailable("pipeline", f"repo não encontrado em {repo}")
        os.environ.setdefault("PT_TO_M", self.cfg.pt_to_m)
        p = str(repo)
        if p not in sys.path:
            sys.path.insert(0, p)
        self._prepared = True

    def _module(self, dotted: str):
        self.prepare()
        try:
            mod = __import__(dotted, fromlist=["_"])
        except Exception as exc:  # noqa: BLE001 — qualquer falha de import é indisponibilidade
            raise PipelineUnavailable(dotted, f"{type(exc).__name__}: {exc}") from exc
        return mod

    # -- o que o host usa ---------------------------------------------------
    def consensus(self) -> dict:
        import json

        path = self.cfg.consensus_path
        if path is None or not Path(path).exists():
            raise PipelineUnavailable("consensus", f"não encontrado ({path})")
        return json.loads(Path(path).read_text("utf-8"))

    def collect_boxes(self, con: dict):
        """Roda o cérebro determinístico de layout do apartamento inteiro."""
        mod = self._module("tools.furnish_apartment")
        fn = getattr(mod, "collect_boxes", None)
        if fn is None:
            raise PipelineUnavailable("collect_boxes", "símbolo ausente em tools.furnish_apartment")
        return fn(con)

    def classify_rooms(self, con: dict):
        mod = self._module("tools.room_type")
        return mod.classify_rooms(con)

    def circulation_gate(self, con: dict, boxes: list, room_id: str) -> dict:
        return self._module("tools.circulation_gate").gate(con, boxes, room_id)

    def overlap_gate_on(self, boxes: list) -> dict:
        """Roda o núcleo PURO do gate de colisão sobre os boxes DADOS.

        `overlap_gate(con, room_id)` recomputa os boxes pelo cérebro e ignoraria a
        edição do agente; `pairwise_overlap(_module_geom(boxes))` é o mesmo cálculo
        aplicado ao que está na cena. Depender de `_module_geom` (privado) é dívida
        NOMEADA — travada por teste, e o caminho de saída é o pipeline publicar uma
        função pública equivalente.
        """
        mod = self._module("tools.furniture_overlap_gate")
        geoms = mod._module_geom(boxes)  # noqa: SLF001 — ver docstring
        fails, warns, n_modules = mod.pairwise_overlap(geoms)
        result = "FAIL" if fails else ("WARN" if warns else "PASS")
        return {"result": result, "n_modules": n_modules, "fails": fails, "warns": warns}

    def geometry_sanity_on(self, boxes: list, rooms_poly: list | None) -> dict:
        mod = self._module("tools.geometry_sanity")
        return mod.audit(boxes, rooms=rooms_poly, to_m=0.0254)

    def room_polygon_in(self, con: dict, room_id: str) -> list | None:
        """Polígono do cômodo em POLEGADAS (mesma unidade dos boxes)."""
        try:
            spatial = self._module("tools.spatial_model")
            scale = self._module("core.scale")
        except PipelineUnavailable:
            return None
        try:
            cell = spatial.build_spatial_model(con, room_id)["_geom"]["cell"]
            return [[x * scale.PT_TO_IN, y * scale.PT_TO_IN] for x, y in cell.exterior.coords]
        except Exception:  # noqa: BLE001 — cômodo sem célula não é erro fatal do gate
            return None

    def route_finding(self, finding_type: str, axis: str | None = None) -> str:
        """Classifica um finding pelo router REAL do pipeline (FP-033)."""
        mod = self._module("tools.finding_router")
        for name in ("route", "route_finding", "classify"):
            fn = getattr(mod, name, None)
            if callable(fn):
                try:
                    return str(fn({"type": finding_type, "axis": axis}))
                except TypeError:
                    try:
                        return str(fn(finding_type))
                    except Exception:  # noqa: BLE001
                        break
                except Exception:  # noqa: BLE001
                    break
        # fallback explícito sobre os conjuntos declarados — não um chute
        if finding_type in getattr(mod, "AUTOFIX_TYPES", frozenset()):
            return mod.DETERMINISTIC_AUTOFIX
        if finding_type in getattr(mod, "NEEDS_VISION_TYPES", frozenset()):
            return mod.NEEDS_VISION
        return mod.NEEDS_FELIPE

    def availability(self) -> dict:
        """O que está realmente disponível AGORA. Usado pelo status do Harness."""
        out: dict[str, object] = {"pipelineRepo": str(self.cfg.pipeline_repo)}
        try:
            self.prepare()
            out["repo"] = "OK"
        except PipelineUnavailable as exc:
            out["repo"] = "MISSING"
            out["detail"] = exc.why
            return out
        for label, dotted in (
            ("furnish", "tools.furnish_apartment"),
            ("circulation", "tools.circulation_gate"),
            ("overlap", "tools.furniture_overlap_gate"),
            ("geometry", "tools.geometry_sanity"),
            ("router", "tools.finding_router"),
        ):
            try:
                self._module(dotted)
                out[label] = "OK"
            except PipelineUnavailable as exc:
                out[label] = "MISSING"
                out[f"{label}Detail"] = exc.why
        out["consensus"] = "OK" if (self.cfg.consensus_path and Path(self.cfg.consensus_path).exists()) else "MISSING"
        return out
