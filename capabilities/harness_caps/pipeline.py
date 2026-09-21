"""ADAPTER para o pipeline real (`sketchup-mcp`).

É o ÚNICO lugar do capability host que importa o repo do pipeline. Todo import é
tardio e guardado: repo ausente, branch sem o módulo ou dependência faltando vira
**indisponibilidade declarada**, nunca um stub que finge ter rodado.

A regra que isto protege (CLAUDE.md §honestidade): uma capability só reporta
sucesso quando o código real rodou. `PipelineUnavailable` é uma resposta legítima;
um PASS inventado não é.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

from .config import HarnessConfig


class PipelineUnavailable(RuntimeError):
    """O pipeline não pôde ser usado — com o motivo REAL, não um genérico."""

    def __init__(self, what: str, why: str):
        super().__init__(f"{what} indisponível: {why}")
        self.what = what
        self.why = why


class SketchUpBusy(RuntimeError):
    """Há um SketchUp aberto e ninguém autorizou fechá-lo.

    Materializar roda o SketchUp em LOTE, e o padrão do pipeline
    (`furnish_apartment`) é `taskkill /F /IM SketchUp.exe` antes e depois. Fazer
    isso por conta própria mataria a janela que o Felipe tem aberta, com trabalho
    possivelmente não salvo. Recusar e dizer por quê é a resposta; fechar exige
    `close_sketchup=True` — autorização explícita, como manda a hard rule #7.
    """


class SketchUpRunner:
    """Tudo que toca processo e relógio de verdade. Dublado inteiro nos testes.

    Existe para que a suíte rode **sem** SketchUp instalado: o `Pipeline` nunca
    chama `subprocess` nem `time` direto no caminho de materialização.
    """

    _IMAGE = "SketchUp.exe"

    def is_running(self) -> bool:
        out = subprocess.run(  # noqa: S603,S607 — comando fixo, sem entrada do usuário
            ["tasklist", "/FI", f"IMAGENAME eq {self._IMAGE}"],
            capture_output=True, text=True,
        )
        return self._IMAGE.lower() in (out.stdout or "").lower()

    def kill(self) -> None:
        subprocess.run(  # noqa: S603,S607 — idem
            ["taskkill", "/F", "/IM", self._IMAGE], capture_output=True)

    def launch(self, cmd: list[str], env: dict) -> None:
        subprocess.Popen(  # noqa: S603 — comando DECLARADO, argumentos validados
            cmd, env=env,
            creationflags=getattr(subprocess, "DETACHED_PROCESS", 0),
        )

    def now(self) -> float:
        return time.time()

    def sleep(self, seconds: float) -> None:
        time.sleep(seconds)


class Pipeline:
    """Fachada fina sobre `sketchup-mcp`. Sem lógica de domínio própria."""

    def __init__(self, cfg: HarnessConfig, runner: SketchUpRunner | None = None):
        self.cfg = cfg
        self.runner = runner or SketchUpRunner()
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

    # -- artefatos .skp -----------------------------------------------------
    #: nomes que NUNCA são "a planta": arquivo de trabalho do SketchUp e scratch.
    _SKP_IGNORE = ("scene.skp", "autosave", "recover", "backup")

    def skp_artifacts(self) -> list[dict]:
        """Os .skp do projeto, do mais recente para o mais antigo.

        Ordena por data de escrita, nunca por nome: arquivo com sufixo de tema
        (`_black_wood_gold`) ordena antes do canônico em ordem alfabética e
        entregaria a planta errada. `scene.skp` é arquivo de trabalho do
        SketchUp e fica de fora — regra do projeto, não heurística.
        """
        # DUAS raízes: os artefatos do pipeline E o que o PRÓPRIO Harness
        # materializou. Sem a segunda, `apply_to_skp` produzia um arquivo que o
        # Harness não conseguia abrir — o modelo achava a tool certa e não achava
        # o arquivo. Gap pego rodando o app (2026-09-21).
        roots = [self.cfg.pipeline_repo / "artifacts" / self.cfg.project,
                 self.materialize_path().parent]
        out = []
        for root in roots:
            if not root.exists():
                continue
            for path in root.rglob("*.skp"):
                name = path.name.lower()
                if any(bad in name for bad in self._SKP_IGNORE):
                    continue
                stat = path.stat()
                if stat.st_size == 0:
                    continue
                out.append({
                    "name": path.name,
                    "path": str(path),
                    "relative": str(path.relative_to(root)),
                    "sizeMb": round(stat.st_size / 1e6, 2),
                    "modified": time.strftime("%Y-%m-%dT%H:%M:%S",
                                              time.localtime(stat.st_mtime)),
                    "source": "harness" if root is roots[1] else "pipeline",
                    "_mtime": stat.st_mtime,
                })
        out.sort(key=lambda item: item["_mtime"], reverse=True)
        for item in out:
            item.pop("_mtime")
        return out

    # -- materializar a cena ------------------------------------------------
    def materialize_path(self) -> Path:
        """Onde o `.skp` da cena editada é escrito.

        Fora do repo do pipeline, de propósito e por duas razões: `sketchup-mcp`
        é dependência de LEITURA do Harness, e o `.skp` canônico que o Felipe
        abre nunca pode ser o destino de uma execução automática.
        `.resolve()` é obrigatório — caminho relativo faz o SketchUp salvar no
        CWD dele e produzir um "saved" falso.
        """
        stamp = time.strftime("%Y%m%d_%H%M%S")
        root = self.cfg.state_dir / "materialized" / self.cfg.project
        return (root / f"{self.cfg.project}_harness_{stamp}.skp").resolve()

    def materialize(self, boxes: list[dict], *, out_path=None,
                    close_sketchup: bool = False, timeout_sec: int = 240) -> dict:
        """Roda o builder do pipeline sobre os boxes DADOS e devolve a evidência.

        Os boxes vêm da cena editada — nunca são recomputados pelo cérebro de
        layout. Recomputar entregaria o `.skp` de sempre e a edição do Felipe
        morreria no documento de cena, que é exatamente o que esta fatia conserta.

        Retorna sempre um dict com `verified`; falha é resultado, não exceção —
        exceto `SketchUpBusy`, que é uma decisão que só o humano toma.
        """
        if not boxes:
            return self._not_materialized("cena sem boxes — nada a materializar")

        dest = Path(out_path).resolve() if out_path else self.materialize_path()
        repo = self.cfg.pipeline_repo.resolve()
        if dest == repo or repo in dest.parents:
            return self._not_materialized(
                f"destino dentro do repo do pipeline ({dest}); `sketchup-mcp` é "
                "dependência de leitura e o .skp canônico nunca é sobrescrito")

        if self.runner.is_running():
            if not close_sketchup:
                raise SketchUpBusy(
                    "há um SketchUp aberto. Materializar roda o SketchUp em lote e "
                    "precisa fechá-lo — o que descartaria trabalho não salvo na "
                    "janela aberta. Feche você mesmo, ou chame de novo com "
                    "close_sketchup=true para autorizar.")
            self.runner.kill()
            self.runner.sleep(1)

        base, rb, exe = self._materialize_inputs()
        log = dest.with_name(dest.stem + "_log.txt")
        dest.parent.mkdir(parents=True, exist_ok=True)
        # apagar ANTES é o que torna "existe e tem tamanho" uma prova de que ESTA
        # execução escreveu — e não o resto de uma execução anterior.
        for stale in (dest, log):
            if stale.exists():
                stale.unlink()

        env = os.environ.copy()
        env["PT_TO_M"] = self.cfg.pt_to_m
        env["LAYOUT_BOXES"] = json.dumps(boxes)
        env["LAYOUT_OUT"] = str(dest).replace("\\", "/")
        env["LAYOUT_LOG"] = str(log).replace("\\", "/")
        # o .skp base é POSICIONAL e vem ANTES de -RubyStartup; invertido, o
        # SketchUp ignora o arquivo e o builder trabalha sobre documento vazio.
        cmd = [str(exe), str(base), "-RubyStartup", str(rb)]

        self.runner.launch(cmd, env)
        deadline = self.runner.now() + timeout_sec
        while self.runner.now() < deadline and not log.exists():
            self.runner.sleep(1)
        timed_out = not log.exists()
        # fecha o LOTE que nós mesmos subimos (não havia outro: ou não existia,
        # ou o fechamento foi autorizado acima).
        self.runner.kill()

        if timed_out:
            return self._not_materialized(
                f"timeout: o SketchUp não produziu log em {timeout_sec}s", path=dest)
        if not dest.exists():
            return self._not_materialized(
                "o builder rodou mas não escreveu o .skp", path=dest,
                log=self._tail(log))
        size = dest.stat().st_size
        if size == 0:
            return self._not_materialized(
                "o .skp saiu com 0 byte — sintoma clássico de falha do SketchUp "
                "em lote", path=dest, log=self._tail(log))
        return {
            "verified": True,
            "path": str(dest),
            "sizeBytes": size,
            "boxes": len(boxes),
            "log": self._tail(log),
        }

    def _materialize_inputs(self) -> tuple[Path, Path, Path]:
        repo = self.cfg.pipeline_repo
        base = repo / "artifacts" / self.cfg.project / f"{self.cfg.project}.skp"
        rb = repo / "tools" / "place_layout_skp.rb"
        exe = Path(self.cfg.sketchup_exe)
        for what, path in (("shell .skp", base), ("place_layout_skp.rb", rb),
                           ("SketchUp", exe)):
            if not path.exists():
                raise PipelineUnavailable(what, f"não encontrado em {path}")
        return base, rb, exe

    @staticmethod
    def _not_materialized(reason: str, *, path=None, log: str | None = None) -> dict:
        out: dict[str, object] = {"verified": False, "reason": reason}
        if path is not None:
            out["path"] = str(path)
        if log:
            out["log"] = log
        return out

    @staticmethod
    def _tail(log: Path, limit: int = 2000) -> str:
        try:
            return log.read_text("utf-8", errors="replace")[-limit:]
        except OSError:
            return ""

    def open_in_sketchup(self, skp_path: str) -> dict:
        """Abre um .skp no SketchUp desta máquina.

        DESANEXADO de propósito: o SketchUp é um aplicativo que o Felipe vai usar,
        não um subprocesso que o capability host precisa vigiar. Se ficasse preso
        ao host, fechar o Harness fecharia o SketchUp junto.
        """
        path = Path(skp_path)
        if not path.exists():
            raise PipelineUnavailable("skp", f"arquivo não existe: {path}")
        exe = Path(self.cfg.sketchup_exe)
        if not exe.exists():
            raise PipelineUnavailable(
                "SketchUp", f"executável não encontrado em {exe} "
                            "(ajuste `sketchupExe` no harness.json)")
        subprocess.Popen(  # noqa: S603 — comando DECLARADO, argumento é caminho validado
            [str(exe), str(path)],
            creationflags=getattr(subprocess, "DETACHED_PROCESS", 0),
            close_fds=True,
        )
        return {"opened": str(path), "exe": str(exe)}

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
