"""Compara modelos locais no MESMO conjunto de comandos do Harness.

Por que existe: a suite de testes roda **sem** Ollama, de proposito — e por isso
nao cobre o planner. Trocar de modelo por impressao e chute. Aqui cada modelo
recebe os mesmos comandos em portugues, a partir do MESMO estado de cena, e e
julgado pelo que ficou NA CENA, nao pelo que ele disse que fez.

    python tools/bench_models.py qwen2.5-coder:14b qwen3:14b

Pre-requisitos: `target/cp.txt` (gerado por
`mvnw dependency:build-classpath -Dmdep.outputFile=target/cp.txt`), Ollama no ar,
e um snapshot de cena para restaurar entre comandos (`SNAPSHOT` abaixo, ou o mais
recente se o nome nao existir).

O julgamento e deterministico:
  OK          fez exatamente o pedido, e so isso
  ALVO_ERRADO mexeu no objeto errado
  INVENTOU    aplicou alteracao que ninguem pediu
  NAO_FEZ     nao aplicou nada
  SEM_FECHO   fez o certo mas nao concluiu (EXHAUSTED)

NAO_FEZ merece leitura cuidadosa: quando uma guarda do Harness recusa (direcao
contraria, cor inventada), o SISTEMA agiu certo e o MODELO errou. A coluna conta
como falha do modelo de proposito — e disso que se trata a comparacao.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
JAVA = Path("C:/Program Files/Eclipse Adoptium/jdk-25.0.2.10-hotspot/bin/java.exe")
SCENE = REPO / "state-local" / "planta_74.scene.json"
SNAPSHOT = "snap_20260920T231056_4"

sys.path.insert(0, str(REPO / "capabilities"))
from harness_caps.config import HarnessConfig  # noqa: E402
from harness_caps.registry import Registry  # noqa: E402


def _registry() -> Registry:
    cfg = HarnessConfig(
        pipeline_repo=Path("E:/Claude/apps/sketchup-mcp"),
        state_dir=REPO / "state-local",
        project="planta_74",
        consensus_path=None,
        pt_to_m="0.0259",
        sketchup_exe="x",
    )
    return Registry(cfg)


def restore() -> None:
    reg = _registry()
    res = reg.invoke("restore_snapshot", {"snapshot_id": SNAPSHOT})
    if not res.get("ok"):
        snaps = reg.invoke("list_snapshots", {}).get("data", {}).get("snapshots", [])
        if not snaps:
            raise SystemExit("sem snapshot para restaurar — rode save_snapshot antes")
        reg.invoke("restore_snapshot", {"snapshot_id": snaps[0]["id"]})


def edits() -> list[dict]:
    return json.loads(SCENE.read_text("utf-8"))["edits"]


def run(model: str, command: str, timeout: int = 240) -> str:
    """Roda UM comando pelo CLI com o modelo dado. Devolve o status do desfecho."""
    cp = (REPO / "target" / "cp.txt").read_text("utf-8").strip()
    cmd = [str(JAVA), f"-DagentModel={model}", "-cp",
           f"{REPO / 'target' / 'classes'};{cp}",
           "harness.cli.HarnessCli", command]
    try:
        out = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True,
                             encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        return "TIMEOUT"
    texto = (out.stdout or "") + (out.stderr or "")
    achou = re.search(r"desfecho:\s*(\w+)", texto)
    return achou.group(1) if achou else "?"


def _fecho(status: str) -> str:
    return "SEM_FECHO" if status == "EXHAUSTED" else "OK"


# -- os casos ---------------------------------------------------------------
def caso_cor_cama(antes: list[dict], depois: list[dict], status: str) -> str:
    novos = depois[len(antes):]
    if not novos:
        return "NAO_FEZ"
    certos = [e for e in novos if e["op"] == "recolor"
              and e["objectId"] == "suite_01.cama" and e.get("colorName") == "preto"]
    if len(novos) > len(certos):
        return "INVENTOU"
    return _fecho(status) if certos else "ALVO_ERRADO"


def caso_move_esquerda(antes: list[dict], depois: list[dict], status: str) -> str:
    novos = depois[len(antes):]
    moves = [e for e in novos if e["op"] == "translate"]
    if not moves:
        return "NAO_FEZ"
    m = moves[0]
    if "escrivaninha" not in m["objectId"] or m.get("dxIn", 0) >= 0:
        return "ALVO_ERRADO"
    if abs(abs(m["dxIn"]) - 11.811) > 0.5:
        return "INVENTOU"  # distancia que ninguem pediu
    return _fecho(status)


def caso_leitura(antes: list[dict], depois: list[dict], status: str) -> str:
    if len(depois) != len(antes):
        return "INVENTOU"  # leitura nao pode mudar a cena
    return _fecho(status)


def caso_undo(antes: list[dict], depois: list[dict], status: str) -> str:
    delta = len(antes) - len(depois)
    if delta == 0:
        return "NAO_FEZ"
    if delta > 1:
        return "INVENTOU"  # desfez mais do que foi pedido
    return _fecho(status)


def caso_cor_sofa(antes: list[dict], depois: list[dict], status: str) -> str:
    novos = depois[len(antes):]
    if not novos:
        return "NAO_FEZ"
    certos = [e for e in novos if e["op"] == "recolor"
              and e["objectId"].endswith(".sofa") and e.get("colorName") == "terracota"]
    if len(novos) > len(certos):
        return "INVENTOU"
    return _fecho(status) if certos else "ALVO_ERRADO"


CASOS = [
    ("pinta a cama da suite 01 de preto", caso_cor_cama),
    ("move a escrivaninha trinta centimetros para a esquerda", caso_move_esquerda),
    ("quais comodos existem nessa planta?", caso_leitura),
    ("desfaz a ultima alteracao", caso_undo),
    ("pinta o sofa da sala de terracota", caso_cor_sofa),
]


def main() -> None:
    modelos = sys.argv[1:]
    if not modelos:
        raise SystemExit(__doc__.strip().splitlines()[0] + "\n\nuso: "
                         "python tools/bench_models.py <modelo> [<modelo> ...]")
    placar: dict[str, list[tuple[str, str, float]]] = {}
    for modelo in modelos:
        print(f"\n{'=' * 66}\nMODELO: {modelo}\n{'=' * 66}")
        linhas = []
        for comando, julgar in CASOS:
            restore()
            antes = edits()
            t0 = time.time()
            status = run(modelo, comando)
            dt = time.time() - t0
            veredito = "TIMEOUT" if status == "TIMEOUT" else julgar(antes, edits(), status)
            print(f"  {veredito:<12} {dt:6.1f}s  {status:<12} {comando[:44]}")
            linhas.append((comando, veredito, dt))
        placar[modelo] = linhas
        restore()

    print(f"\n{'=' * 66}\nPLACAR\n{'=' * 66}")
    for modelo, linhas in placar.items():
        ok = sum(1 for _c, v, _t in linhas if v == "OK")
        inventou = sum(1 for _c, v, _t in linhas if v == "INVENTOU")
        media = sum(t for _c, _v, t in linhas) / len(linhas)
        print(f"  {modelo:<26} OK={ok}/{len(linhas)}  INVENTOU={inventou}  "
              f"media={media:.1f}s")


if __name__ == "__main__":
    main()
