"""Testes do Tool Registry — o contrato.

O que estes testes travam: o modelo não consegue inventar tool, nem argumento,
nem id de objeto; risco e reversibilidade são declarados; e toda falha vira
resultado TIPADO (o agente precisa poder reagir ao erro, não engasgar com ele).
"""
from __future__ import annotations

import pytest

from harness_caps.config import HarnessConfig
from harness_caps.registry import UNSUPPORTED, Registry
from harness_caps.scene import SceneStore


def _cfg(tmp_path) -> HarnessConfig:
    return HarnessConfig(
        pipeline_repo=tmp_path / "sem-pipeline",
        state_dir=tmp_path / "state",
        project="planta_74",
        consensus_path=None,
        pt_to_m="0.0259",
    )


def _box(room="SUITE 02", module="Escrivaninha", kind="desk", x0=100.0, y0=200.0):
    return {"room": room, "module": module, "kind": kind, "label": module,
            "x0": x0, "y0": y0, "x1": x0 + 40, "y1": y0 + 20,
            "z0_in": 0.0, "h_in": 29.0,
            "corners": [[x0, y0], [x0 + 40, y0], [x0 + 40, y0 + 20], [x0, y0 + 20]]}


@pytest.fixture()
def reg(tmp_path):
    r = Registry(_cfg(tmp_path))
    r.store.build(
        project="planta_74",
        rooms=[{"id": "r003", "name": "SUITE 02", "type": "BEDROOM"},
               {"id": "r000", "name": "SUITE 01", "type": "BEDROOM"}],
        boxes=[_box(), _box(room="SUITE 01", module="Cama", kind="bed", x0=300)],
        source={"builder": "teste"})
    return r


# -- contrato ---------------------------------------------------------------
def test_describe_publica_schema_risco_e_reversibilidade(reg):
    tools = {t["name"]: t for t in reg.describe()["tools"]}
    assert "move_object" in tools
    mv = tools["move_object"]
    assert mv["risk"] == "LOW"
    assert mv["undoable"] is True
    assert mv["mutates"] is True
    assert "object_id" in mv["inputSchema"]["required"]


def test_describe_publica_o_que_ainda_NAO_existe(reg):
    unsupported = {u["name"] for u in reg.describe()["unsupported"]}
    assert "render" in unsupported and "rotate_object" in unsupported
    assert unsupported == set(UNSUPPORTED)


def test_tool_desconhecida_nao_vira_comando(reg):
    out = reg.invoke("rm_rf", {"path": "/"})
    assert out["ok"] is False
    assert out["error"]["code"] == "UNKNOWN_TOOL"
    assert "move_object" in out["error"]["available"]


def test_capability_ainda_nao_suportada_explica_o_porque(reg):
    out = reg.invoke("render", {})
    assert out["error"]["code"] == "UNKNOWN_TOOL"
    assert "slice 5" in out["error"]["message"]


def test_argumento_obrigatorio_faltando_e_erro_tipado(reg):
    out = reg.invoke("move_object", {"object_id": "suite_02.escrivaninha"})
    assert out["error"]["code"] == "INVALID_ARGUMENTS"
    assert "direction" in out["error"]["message"]


def test_argumento_inventado_e_recusado_em_vez_de_ignorado(reg):
    out = reg.invoke("move_object", {"object_id": "suite_02.escrivaninha",
                                     "direction": "left", "distance_mm": 10,
                                     "axis": "x"})
    assert out["error"]["code"] == "INVALID_ARGUMENTS"
    assert "axis" in out["error"]["message"]


def test_tipo_errado_e_recusado(reg):
    out = reg.invoke("move_object", {"object_id": "suite_02.escrivaninha",
                                     "direction": "left", "distance_mm": "trinta"})
    assert out["error"]["code"] == "INVALID_ARGUMENTS"


def test_direcao_fora_do_enum_e_recusada(reg):
    out = reg.invoke("move_object", {"object_id": "suite_02.escrivaninha",
                                     "direction": "diagonal", "distance_mm": 10})
    assert out["error"]["code"] == "INVALID_ARGUMENTS"


# -- comportamento ----------------------------------------------------------
def test_move_converte_mm_para_polegada_e_relata_o_centro_em_metros(reg):
    out = reg.invoke("move_object", {"object_id": "suite_02.escrivaninha",
                                     "direction": "left", "distance_mm": 300})
    assert out["ok"] is True
    d = out["data"]
    assert d["edit"]["dxIn"] == pytest.approx(-11.811, abs=1e-3)
    assert d["centerBeforeM"][0] - d["centerAfterM"][0] == pytest.approx(0.3, abs=1e-3)


def test_distancia_negativa_e_recusada_porque_a_direcao_ja_diz_o_sentido(reg):
    out = reg.invoke("move_object", {"object_id": "suite_02.escrivaninha",
                                     "direction": "left", "distance_mm": -300})
    assert out["error"]["code"] == "SCENE_ERROR"


def test_move_aceita_termo_em_vez_de_id_e_resolve(reg):
    out = reg.invoke("move_object", {"object_id": "escrivaninha",
                                     "direction": "right", "distance_mm": 100})
    assert out["data"]["objectId"] == "suite_02.escrivaninha"


def test_termo_ambiguo_devolve_candidatos_em_vez_de_escolher(tmp_path):
    r = Registry(_cfg(tmp_path))
    r.store.build(project="p",
                  rooms=[{"id": "r1", "name": "SUITE 01", "type": "BEDROOM"},
                         {"id": "r2", "name": "SUITE 02", "type": "BEDROOM"}],
                  boxes=[_box(room="SUITE 01"), _box(room="SUITE 02")], source={})
    out = r.invoke("move_object", {"object_id": "escrivaninha",
                                   "direction": "left", "distance_mm": 100})
    assert out["error"]["code"] == "AMBIGUOUS"
    assert len(out["error"]["candidates"]) == 2


def test_objeto_travado_bloqueia_o_agente(reg):
    reg.invoke("lock_object", {"object_id": "suite_01.cama"})
    out = reg.invoke("move_object", {"object_id": "suite_01.cama",
                                     "direction": "left", "distance_mm": 100})
    assert out["error"]["code"] == "SCENE_ERROR"
    assert "travado" in out["error"]["message"]


def test_undo_desfaz_o_ultimo_move(reg):
    reg.invoke("move_object", {"object_id": "suite_02.escrivaninha",
                               "direction": "left", "distance_mm": 300})
    out = reg.invoke("undo", {})
    assert out["data"]["undone"] is True
    assert reg.invoke("list_history", {})["data"]["count"] == 0


def test_undo_sem_nada_para_desfazer_nao_mente(reg):
    out = reg.invoke("undo", {})
    assert out["ok"] is True and out["data"]["undone"] is False


def test_find_object_nao_devolve_unique_quando_ha_duvida(tmp_path):
    r = Registry(_cfg(tmp_path))
    r.store.build(project="p",
                  rooms=[{"id": "r1", "name": "SUITE 01", "type": "BEDROOM"},
                         {"id": "r2", "name": "SUITE 02", "type": "BEDROOM"}],
                  boxes=[_box(room="SUITE 01"), _box(room="SUITE 02")], source={})
    out = r.invoke("find_object", {"query": "escrivaninha"})
    assert out["data"]["count"] == 2
    assert out["data"]["unique"] is None


def test_gates_sem_pipeline_reportam_indisponibilidade_nao_pass(reg):
    out = reg.invoke("run_gates", {"room_id": "r003"})
    assert out["ok"] is False
    assert out["error"]["code"] == "PIPELINE_UNAVAILABLE"


def test_status_diz_que_o_pipeline_nao_esta_la(reg):
    out = reg.invoke("get_system_status", {})
    assert out["data"]["pipeline"]["repo"] == "MISSING"
    assert out["data"]["scene"]["loaded"] is True


def test_snapshot_marcado_clean_volta_pelo_restore_last_clean(reg):
    reg.invoke("save_snapshot", {"label": "base", "clean": True})
    reg.invoke("move_object", {"object_id": "suite_02.escrivaninha",
                               "direction": "left", "distance_mm": 500})
    reg.invoke("restore_last_clean", {})
    assert reg.invoke("list_history", {})["data"]["count"] == 0


def test_restore_last_clean_sem_clean_marcado_explica(reg):
    out = reg.invoke("restore_last_clean", {})
    assert out["error"]["code"] == "SCENE_ERROR"
    assert "CLEAN" in out["error"]["message"]


def test_handler_que_explode_nao_derruba_o_registry(reg, monkeypatch):
    monkeypatch.setattr(reg.store, "objects",
                        lambda *a, **k: (_ for _ in ()).throw(ValueError("boom")))
    out = reg.invoke("list_objects", {})
    assert out["error"]["code"] == "HANDLER_ERROR"
    assert "boom" in out["error"]["message"]


def test_toda_tool_que_muta_declara_risco_e_reversibilidade(reg):
    for t in reg.describe()["tools"]:
        if t["mutates"]:
            assert t["risk"] in ("LOW", "MEDIUM", "HIGH"), t["name"]


def test_cena_ausente_orienta_em_vez_de_estourar(tmp_path):
    r = Registry(_cfg(tmp_path))
    out = r.invoke("list_objects", {})
    assert out["error"]["code"] == "SCENE_ERROR"
    assert "open_project" in out["error"]["message"]
