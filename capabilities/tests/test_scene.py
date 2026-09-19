"""Testes da cena: identidade, translação, histórico, travas, snapshots.

Nenhum destes precisa do pipeline, do SketchUp ou do Ollama — a cena é um
documento e as regras sobre ele são puras. É isso que permite o CI verde sem
abrir o SketchUp (missão §29).
"""
from __future__ import annotations

import json

import pytest

from harness_caps.scene import (AmbiguousObject, SceneError, SceneStore, bbox_of,
                                normalize_query, slug, translate_box)


def _box(room="SUITE 02", module="Escrivaninha", kind="desk",
         x0=100.0, y0=200.0, w=40.0, d=20.0, z0=0.0, h=29.0):
    return {
        "room": room, "module": module, "kind": kind, "label": module,
        "x0": x0, "y0": y0, "x1": x0 + w, "y1": y0 + d,
        "z0_in": z0, "h_in": h,
        "corners": [[x0, y0], [x0 + w, y0], [x0 + w, y0 + d], [x0, y0 + d]],
    }


@pytest.fixture()
def store(tmp_path):
    s = SceneStore(tmp_path / "p.scene.json", tmp_path / "snaps")
    s.build(
        project="planta_74",
        rooms=[{"id": "r003", "name": "SUITE 02", "type": "BEDROOM"},
               {"id": "r002", "name": "SALA", "type": "LIVING"}],
        boxes=[_box(), _box(module="Cama", kind="bed", x0=300, y0=200, w=60, d=80),
               _box(room="SALA", module="Sofa", kind="sofa", x0=10, y0=10, w=80, d=35)],
        source={"builder": "teste"})
    return s


def test_slug_normaliza_acento_e_separador():
    assert slug("SALA DE JANTAR | SALA DE ESTAR") == "sala_de_jantar_sala_de_estar"
    assert slug("Criado-mudo 1") == "criado_mudo_1"


def test_translate_move_extremos_corners_e_profile_mas_nao_a_direcao_de_extrusao():
    b = _box()
    b["profile_world"] = [[100.0, 200.0, 5.0]]
    b["extrude_vec"] = [-31.89, 0.0, 0.0]
    moved = translate_box(b, -10.0, 2.0, 1.0)
    assert moved["x0"] == 90.0 and moved["x1"] == 130.0
    assert moved["y0"] == 202.0
    assert moved["z0_in"] == 1.0
    assert moved["corners"][0] == [90.0, 202.0]
    assert moved["profile_world"][0] == [90.0, 202.0, 6.0]
    assert moved["extrude_vec"] == [-31.89, 0.0, 0.0], "extrude_vec é direção, não posição"
    assert b["x0"] == 100.0, "o box original não pode ser mutado"


def test_bbox_of_devolve_none_quando_nao_ha_extensao():
    assert bbox_of([{"kind": "x"}]) is None


def test_objetos_agrupam_por_modulo_e_ganham_id_estavel(store):
    ids = sorted(o.id for o in store.objects())
    assert ids == ["sala.sofa", "suite_02.cama", "suite_02.escrivaninha"]
    desk = store.object("suite_02.escrivaninha")
    assert desk.room_id == "r003"
    assert desk.part_count == 1


def test_move_desloca_e_fica_no_historico(store):
    antes = store.object("suite_02.escrivaninha").bbox_in["x0"]
    store.translate("suite_02.escrivaninha", -11.811, 0, 0, reason="teste")
    depois = store.object("suite_02.escrivaninha").bbox_in["x0"]
    assert round(antes - depois, 3) == 11.811
    assert len(store.history()) == 1
    assert store.history()[0]["reason"] == "teste"


def test_move_so_mexe_no_objeto_alvo(store):
    cama = store.object("suite_02.cama").bbox_in
    store.translate("suite_02.escrivaninha", -20, 0, 0)
    assert store.object("suite_02.cama").bbox_in == cama


def test_undo_volta_exatamente_a_posicao_anterior(store):
    antes = store.object("suite_02.escrivaninha").bbox_in
    store.translate("suite_02.escrivaninha", -11.811, 3.2, 0)
    assert store.object("suite_02.escrivaninha").bbox_in != antes
    store.undo()
    assert store.object("suite_02.escrivaninha").bbox_in == antes


def test_undo_sem_edicao_devolve_none(store):
    assert store.undo() is None


def test_redo_reaplica_a_edicao_desfeita(store):
    store.translate("suite_02.escrivaninha", -10, 0, 0)
    movido = store.object("suite_02.escrivaninha").bbox_in
    store.undo()
    store.redo()
    assert store.object("suite_02.escrivaninha").bbox_in == movido


def test_objeto_travado_recusa_movimento(store):
    store.lock("suite_02.cama")
    with pytest.raises(SceneError, match="travado"):
        store.translate("suite_02.cama", 10, 0, 0)


def test_unlock_libera_de_novo(store):
    store.lock("suite_02.cama")
    store.unlock("suite_02.cama")
    store.translate("suite_02.cama", 1, 0, 0)
    assert len(store.history()) == 1


def test_find_prefere_casamento_exato_a_substring(store):
    hits = [o.id for o in store.find("cama")]
    assert hits == ["suite_02.cama"], "substring não pode arrastar vizinho junto"


def test_find_casa_termo_em_linguagem_natural(store):
    assert [o.id for o in store.find("escrivaninha")] == ["suite_02.escrivaninha"]


def test_resolve_ambiguo_levanta_com_candidatos(tmp_path):
    s = SceneStore(tmp_path / "p.scene.json", tmp_path / "s")
    s.build(project="p",
            rooms=[{"id": "r1", "name": "SUITE 01", "type": "BEDROOM"},
                   {"id": "r2", "name": "SUITE 02", "type": "BEDROOM"}],
            boxes=[_box(room="SUITE 01"), _box(room="SUITE 02")],
            source={})
    with pytest.raises(AmbiguousObject) as exc:
        s.resolve_one("escrivaninha")
    assert len(exc.value.candidates) == 2


def test_resolve_usa_o_comodo_ativo_para_desempatar(tmp_path):
    s = SceneStore(tmp_path / "p.scene.json", tmp_path / "s")
    s.build(project="p",
            rooms=[{"id": "r1", "name": "SUITE 01", "type": "BEDROOM"},
                   {"id": "r2", "name": "SUITE 02", "type": "BEDROOM"}],
            boxes=[_box(room="SUITE 01"), _box(room="SUITE 02")],
            source={})
    assert s.resolve_one("escrivaninha", room_id="r2").room == "SUITE 02"


def test_resolve_usa_o_ultimo_referenciado_quando_o_comodo_nao_desempata(tmp_path):
    s = SceneStore(tmp_path / "p.scene.json", tmp_path / "s")
    s.build(project="p",
            rooms=[{"id": "r1", "name": "SUITE 01", "type": "BEDROOM"},
                   {"id": "r2", "name": "SUITE 02", "type": "BEDROOM"}],
            boxes=[_box(room="SUITE 01"), _box(room="SUITE 02")],
            source={})
    got = s.resolve_one("escrivaninha", last_referenced="suite_02.escrivaninha")
    assert got.id == "suite_02.escrivaninha"


def test_termo_sem_correspondencia_falha_em_vez_de_chutar(store):
    with pytest.raises(SceneError, match="não encontrei"):
        store.resolve_one("banheira de hidromassagem")


def test_snapshot_e_restore_voltam_o_documento_inteiro(store):
    snap = store.snapshot("antes")
    store.translate("suite_02.escrivaninha", -50, 0, 0)
    assert len(store.history()) == 1
    store.restore(snap["id"])
    assert len(store.history()) == 0


def test_restore_de_snapshot_inexistente_falha_claro(store):
    with pytest.raises(SceneError, match="não existe"):
        store.restore("snap_fantasma")


def test_marca_clean_e_le_de_volta(store):
    snap = store.snapshot()
    store.mark_clean(snap["id"])
    assert store.last_clean() == snap["id"]


def test_cena_inexistente_diz_como_resolver(tmp_path):
    s = SceneStore(tmp_path / "nada.json", tmp_path / "s")
    with pytest.raises(SceneError, match="open_project"):
        s.load()


def test_schema_incompativel_e_recusado(tmp_path):
    p = tmp_path / "p.scene.json"
    p.write_text(json.dumps({"schemaVersion": 99}), "utf-8")
    with pytest.raises(SceneError, match="schema"):
        SceneStore(p, tmp_path / "s").load()


def test_estado_corrente_e_baseline_mais_edicoes_reaplicadas_do_disco(store, tmp_path):
    store.translate("suite_02.escrivaninha", -10, 0, 0)
    store.translate("suite_02.escrivaninha", -10, 0, 0)
    recarregado = SceneStore(store.path, store.snapshot_dir)
    assert recarregado.object("suite_02.escrivaninha").bbox_in["x0"] == 80.0


def test_artigo_e_preposicao_saem_do_termo_do_usuario():
    assert normalize_query("a escrivaninha") == "escrivaninha"
    assert normalize_query("a mesa de jantar") == "mesa_jantar"
    assert normalize_query("o sofa") == "sofa"


def _dois_moveis(tmp_path):
    s = SceneStore(tmp_path / "p.scene.json", tmp_path / "snaps")
    s.build(project="p",
            rooms=[{"id": "r000", "name": "SUITE 01", "type": "BEDROOM"}],
            boxes=[_box(room="SUITE 01", module="Escrivaninha", kind="top"),
                   _box(room="SUITE 01", module="Cadeira escrivaninha", kind="seat", x0=180)],
            source={})
    return s


def test_artigo_nao_faz_a_cadeira_da_mesa_virar_candidata(tmp_path):
    """Regressao real (2026-09-19): "move a escrivaninha" pedia desambiguacao
    entre a Escrivaninha e a Cadeira escrivaninha. O artigo quebrava o casamento
    exato e jogava a busca no substring, onde a cadeira tambem casa."""
    store = _dois_moveis(tmp_path)
    assert [o.id for o in store.find("a escrivaninha")] == ["suite_01.escrivaninha"]
    assert store.resolve_one("a escrivaninha").id == "suite_01.escrivaninha"


def test_a_cadeira_ainda_e_encontravel_pelo_proprio_nome(tmp_path):
    store = _dois_moveis(tmp_path)
    assert [o.id for o in store.find("cadeira escrivaninha")] == [
        "suite_01.cadeira_escrivaninha"]


def test_o_nome_visivel_do_objeto_e_o_do_modulo_nao_o_da_peca(tmp_path):
    """Regressao real: a escrivaninha aparecia como "top" (rotulo da 1a peca)."""
    st = SceneStore(tmp_path / "p.scene.json", tmp_path / "s")
    peca = _box(room="SUITE 01", module="Escrivaninha", kind="top")
    peca["label"] = "top"
    st.build(project="p", rooms=[{"id": "r000", "name": "SUITE 01", "type": "BEDROOM"}],
             boxes=[peca], source={})
    assert st.object("suite_01.escrivaninha").label == "Escrivaninha"
