"""Testes do protocolo NDJSON do capability host.

O que importa aqui: o host responde SEMPRE na mesma ordem, com o `id` do pedido,
e nunca morre por causa de uma linha ruim. Se ele cair, o Harness perde o plano
de execução inteiro.
"""
from __future__ import annotations

import io
import json

import pytest

from harness_caps import host as host_mod
from harness_caps.config import HarnessConfig


@pytest.fixture(autouse=True)
def _cfg(tmp_path, monkeypatch):
    cfg = HarnessConfig(pipeline_repo=tmp_path / "sem-pipeline",
                        state_dir=tmp_path / "state", project="planta_74",
                        consensus_path=None, pt_to_m="0.0259")
    monkeypatch.setattr(host_mod, "load", lambda: cfg)
    return cfg


def _run(lines: list[dict]) -> list[dict]:
    stdin = io.StringIO("".join(json.dumps(x) + "\n" for x in lines))
    stdout = io.StringIO()
    host_mod.serve(stdin=stdin, stdout=stdout, stderr=io.StringIO())
    return [json.loads(l) for l in stdout.getvalue().splitlines() if l.strip()]


def test_anuncia_prontidao_antes_de_qualquer_pedido():
    out = _run([])
    assert out[0]["event"] == "ready"
    assert out[0]["toolCount"] > 0


def test_ping_responde_com_o_id_do_pedido():
    out = _run([{"id": "abc", "method": "ping"}])
    assert out[1]["id"] == "abc" and out[1]["data"]["pong"] is True
    assert "elapsedMs" in out[1]


def test_describe_entrega_a_tabela_de_tools():
    out = _run([{"id": "1", "method": "describe"}])
    names = {t["name"] for t in out[1]["data"]["tools"]}
    assert {"move_object", "run_gates", "undo", "find_object"} <= names


def test_linha_ilegivel_nao_derruba_o_host():
    stdin = io.StringIO('nao é json\n' + json.dumps({"id": "2", "method": "ping"}) + "\n")
    stdout = io.StringIO()
    host_mod.serve(stdin=stdin, stdout=stdout, stderr=io.StringIO())
    out = [json.loads(l) for l in stdout.getvalue().splitlines()]
    assert out[1]["error"]["code"] == "BAD_JSON"
    assert out[2]["data"]["pong"] is True, "o host segue atendendo depois do lixo"


def test_metodo_desconhecido_vira_erro_tipado():
    out = _run([{"id": "1", "method": "exec"}])
    assert out[1]["error"]["code"] == "UNKNOWN_METHOD"


def test_invoke_de_tool_inexistente_nao_vira_comando():
    out = _run([{"id": "1", "method": "invoke", "tool": "shell",
                 "args": {"cmd": "del /f /s /q C:"}}])
    assert out[1]["error"]["code"] == "UNKNOWN_TOOL"


def test_ordem_das_respostas_segue_a_dos_pedidos():
    out = _run([{"id": "a", "method": "ping"},
                {"id": "b", "method": "describe"},
                {"id": "c", "method": "ping"}])
    assert [r["id"] for r in out[1:]] == ["a", "b", "c"]


def test_shutdown_encerra_e_confirma():
    out = _run([{"id": "x", "method": "shutdown"}, {"id": "y", "method": "ping"}])
    assert out[-1]["data"] == {"bye": True}
    assert all(r.get("id") != "y" for r in out), "nada é atendido depois do shutdown"
