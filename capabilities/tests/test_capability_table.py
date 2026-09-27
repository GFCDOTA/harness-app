"""A tabela de capabilities commitada tem que ser a que o registry gera AGORA.

Este teste e o que impede o drift de voltar. A `CAPABILITY_MATRIX.md` era mantida
a mao em paralelo ao registry e as duas derivaram ate o documento afirmar READY
para tool que nao existia com aquele nome. Num app cujo proposito e ENSINAR como o
sistema funciona, documento que mente e o pior defeito possivel.

Se este teste quebrar, nao edite o .md: rode

    python tools/capability_table.py --write

e confira o diff. Ele e a prova de que alguem mexeu no registry — o que e normal —
e de que a documentacao precisa acompanhar no mesmo commit.
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
TABELA = REPO / "docs" / "CAPABILITY_TABLE.md"
GERADOR = REPO / "tools" / "capability_table.py"


def _render() -> str:
    spec = importlib.util.spec_from_file_location("capability_table", GERADOR)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules["capability_table"] = mod
    spec.loader.exec_module(mod)
    return mod.render()


@pytest.mark.skipif(not GERADOR.exists(), reason="gerador ausente")
def test_a_tabela_commitada_e_a_que_o_registry_gera():
    assert TABELA.exists(), (
        "docs/CAPABILITY_TABLE.md nao existe — rode "
        "`python tools/capability_table.py --write`")

    esperado = _render()
    atual = TABELA.read_text(encoding="utf-8")

    assert atual == esperado, (
        "a tabela commitada divergiu do registry. NAO edite o .md a mao: rode "
        "`python tools/capability_table.py --write` e commite junto com a mudanca "
        "do registry.")


def test_a_tabela_nao_inventa_estado_que_o_runtime_nao_sabe():
    """`PARTIAL` e julgamento humano; `BLOCKED_BY_GUARD` e desfecho de CHAMADA.

    Nenhum dos dois e estado de capability. Deixar qualquer um virar contagem aqui
    recriaria o drift em outro lugar — a contagem pareceria medida e nao seria.
    """
    texto = _render()

    assert "IMPLEMENTED" in texto
    assert "UNAVAILABLE" in texto
    # aparecem so na PROSA que explica por que nao sao contagem
    assert "| PARTIAL |" not in texto
    assert "BLOCKED_BY_GUARD" not in texto
