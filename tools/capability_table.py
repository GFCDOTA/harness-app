"""Gera a tabela de capabilities A PARTIR DO REGISTRY. Fonte única.

    python tools/capability_table.py            # imprime
    python tools/capability_table.py --write    # grava em docs/CAPABILITY_TABLE.md

Por que existe: a `CAPABILITY_MATRIX.md` era mantida à mão em paralelo ao registry
e as duas derivaram. Em 2026-09-27 o diagnóstico era 40 linhas na matriz contra 36
tools no runtime, **17 nomes sem tool** e **13 tools sem linha** — incluindo
entradas marcadas READY para tool que não existe com aquele nome (`get_scene`,
`save_project`, `snapshot`, `status`).

Documento dizendo READY para capability inexistente é o pior defeito possível num
app cujo propósito é ENSINAR como o sistema funciona. Então a tabela deixa de ser
escrita e passa a ser gerada.

Taxonomia: só o que o runtime sabe afirmar.

  IMPLEMENTED   implemented=true — existe e executa
  UNAVAILABLE   implemented=false — registrada e RECUSA com motivo

`PARTIAL` NÃO é estado de runtime: é julgamento humano sobre qualidade
("move_object só mexe na cena", "open_sketchup não verifica"). Enquanto o registry
não carregar esse campo, ele vive na prosa da matriz — não numa contagem que finge
ser medida. E `BLOCKED_BY_GUARD` não é estado de capability: é desfecho de uma
CHAMADA. Misturar os dois recriaria o drift em outro lugar.
"""
from __future__ import annotations

import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
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


def render() -> str:
    described = _registry().describe()
    tools = sorted(described["tools"], key=lambda t: t["name"])
    motivos = {u["name"]: u["reason"] for u in described["unsupported"]}
    feitas = [t for t in tools if t["implemented"]]
    recusam = [t for t in tools if not t["implemented"]]

    linhas: list[str] = []
    linhas.append("# Tabela de capabilities — GERADA, não escrita")
    linhas.append("")
    linhas.append("> ⚠️ **Não editar à mão.** Saída de `tools/capability_table.py`, lida do")
    linhas.append("> registry em `capabilities/harness_caps/registry.py`. Se esta tabela")
    linhas.append("> discorda do runtime, o runtime está certo e o gerador é que quebrou.")
    linhas.append("")
    linhas.append(f"**{len(tools)} capabilities publicadas** — "
                  f"{len(feitas)} IMPLEMENTED · {len(recusam)} UNAVAILABLE")
    linhas.append("")
    linhas.append("`PARTIAL` não aparece aqui: é julgamento humano sobre qualidade, não")
    linhas.append("estado que o runtime saiba afirmar. Vive na prosa da")
    linhas.append("[`CAPABILITY_MATRIX.md`](CAPABILITY_MATRIX.md), não numa contagem.")
    linhas.append("")

    linhas.append("## IMPLEMENTED")
    linhas.append("")
    linhas.append("| capability | muda estado | reversível | verificação | risco | exige |")
    linhas.append("|---|---|---|---|---|---|")
    for t in feitas:
        linhas.append(
            f"| `{t['name']}` | {'sim' if t['mutates'] else 'leitura'} "
            f"| {'sim' if t['undoable'] else '—'} "
            f"| {t['verification']} | {t['risk']} "
            f"| {', '.join(t['requires']) or '—'} |")
    linhas.append("")

    linhas.append("## UNAVAILABLE — registrada, recusa com motivo")
    linhas.append("")
    linhas.append("Estas existem no vocabulário de propósito: o modelo escolhe tool por")
    linhas.append("nome e ignora proibição em prosa. Chamar devolve o motivo.")
    linhas.append("")
    linhas.append("| capability | por que ainda não existe |")
    linhas.append("|---|---|")
    for t in recusam:
        linhas.append(f"| `{t['name']}` | {motivos.get(t['name'], '—')} |")
    linhas.append("")
    return "\n".join(linhas) + "\n"


def main() -> None:
    saida = render()
    if "--write" in sys.argv:
        destino = REPO / "docs" / "CAPABILITY_TABLE.md"
        destino.write_text(saida, encoding="utf-8")
        print(f"gravado: {destino.relative_to(REPO)}")
    else:
        sys.stdout.write(saida)


if __name__ == "__main__":
    main()
