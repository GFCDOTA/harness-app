"""Testes do slice 4 — `set_color`: "troca a cor da cama para preto".

É o pedido que originou o projeto inteiro. O que estes testes travam:

- **Cor é tabela, não palpite.** Nome desconhecido RECUSA com a lista. Um RGB
  plausível inventado pelo modelo entraria no `.skp` como se tivesse sido pedido.
- **Nome de material próprio, senão a cor não aparece.** `place_layout_skp.rb`
  faz `m = model.materials[name]; return m if m` — material existente é reusado
  **pelo nome** e o `rgb` novo é IGNORADO. Mudar só a cor, mantendo o
  `ph_<kind>` compartilhado, daria um `.skp` visualmente idêntico, em silêncio.
  Esta é a armadilha central da fatia.
- **Cor entra no mesmo log de edições do move**, então undo sai de graça.
- **Gate de geometria não roda**: cor não move nada. E não fingir que rodou.
"""
from __future__ import annotations

import pytest

from harness_caps import colors
from harness_caps.config import HarnessConfig
from harness_caps.registry import Registry


def _cfg(tmp_path) -> HarnessConfig:
    return HarnessConfig(
        pipeline_repo=tmp_path / "sem-pipeline",
        state_dir=tmp_path / "state",
        project="planta_74",
        consensus_path=None,
        pt_to_m="0.0259",
        sketchup_exe=str(tmp_path / "sem-sketchup.exe"),
    )


def _box(room="SUITE 01", module="Cama", kind="bed", x0=300.0, rgb=None):
    box = {"room": room, "module": module, "kind": kind, "label": module,
           "x0": x0, "y0": 200.0, "x1": x0 + 60, "y1": 280.0,
           "z0_in": 0.0, "h_in": 20.0}
    if rgb is not None:
        box["rgb"] = list(rgb)
    return box


@pytest.fixture()
def reg(tmp_path):
    r = Registry(_cfg(tmp_path))
    r.store.build(
        project="planta_74",
        rooms=[{"id": "r000", "name": "SUITE 01", "type": "BEDROOM"},
               {"id": "r003", "name": "SUITE 02", "type": "BEDROOM"}],
        # a cama tem 2 peças: colchao e base. Cor tem que pegar as DUAS.
        boxes=[_box(kind="bed", rgb=(200, 200, 200)),
               _box(kind="bed_base", x0=300.0, rgb=(200, 200, 200)),
               _box(room="SUITE 02", module="Escrivaninha", kind="desk", x0=100.0)],
        source={"builder": "teste"})
    return r


# -- a tabela de cores -------------------------------------------------------
def test_nome_de_cor_vira_rgb_deterministico():
    assert colors.resolve("preto") == ("preto", (26, 26, 28))
    assert colors.resolve("PRETO")[1] == (26, 26, 28)
    assert colors.resolve("  preta ")[1] == (26, 26, 28)


def test_ingles_e_espaco_resolvem_para_o_mesmo_canonico():
    assert colors.resolve("black")[0] == "preto"
    assert colors.resolve("dark wood")[0] == "madeira-escura"
    assert colors.resolve("cinza claro")[0] == "cinza-claro"


def test_cor_desconhecida_recusa_com_a_lista_e_nao_chuta():
    with pytest.raises(colors.UnknownColor) as exc:
        colors.resolve("azul-petroleo-fosco")
    assert "preto" in exc.value.available
    assert "não existe" in str(exc.value)


# -- o efeito na cena --------------------------------------------------------
def test_set_color_pinta_todas_as_pecas_do_modulo(reg):
    res = reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})

    assert res["ok"] is True, res
    data = res["data"]
    assert data["partsPainted"] == 2
    assert data["rgbAfter"] == [26, 26, 28]
    for box in reg.store.boxes_of("suite_01.cama"):
        assert box["rgb"] == [26, 26, 28]


def test_cor_nova_ganha_nome_de_material_proprio(reg):
    """Sem isto o builder reusa `ph_bed` e a cor nova nunca aparece no .skp."""
    antes = {b.get("mat_name") for b in reg.store.boxes_of("suite_01.cama")}

    reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})

    depois = {b["mat_name"] for b in reg.store.boxes_of("suite_01.cama")}
    assert depois != antes
    assert all(n.endswith("1a1a1c") for n in depois), depois
    # nome estavel: repintar da mesma cor da o mesmo material
    reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "black"})
    assert {b["mat_name"] for b in reg.store.boxes_of("suite_01.cama")} == depois


def test_material_nao_colide_entre_objetos_da_mesma_cor(reg):
    reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})
    reg.invoke("set_color", {"object_id": "suite_02.escrivaninha", "color": "preto"})

    cama = {b["mat_name"] for b in reg.store.boxes_of("suite_01.cama")}
    mesa = {b["mat_name"] for b in reg.store.boxes_of("suite_02.escrivaninha")}
    assert cama.isdisjoint(mesa)


def test_so_o_objeto_pedido_muda_de_cor(reg):
    reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})

    outra = reg.store.boxes_of("suite_02.escrivaninha")[0]
    assert outra.get("rgb") != [26, 26, 28]


# -- undo de graça -----------------------------------------------------------
def test_undo_restaura_a_cor_exata(reg):
    reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})

    reg.invoke("undo", {})

    for box in reg.store.boxes_of("suite_01.cama"):
        assert box["rgb"] == [200, 200, 200]


def test_cor_entra_no_mesmo_log_de_edicoes_do_move(reg):
    reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})
    reg.invoke("move_object", {"object_id": "suite_01.cama",
                               "direction": "left", "distance_mm": 100})

    ops = [e["op"] for e in reg.store.history()]
    assert ops == ["recolor", "translate"]
    assert reg.store.unmaterialized_edits() == 2


def test_recolor_sobrevive_a_um_move_depois(reg):
    """As edits sao reaplicadas em ordem sobre a baseline; uma nao apaga a outra."""
    reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})
    reg.invoke("move_object", {"object_id": "suite_01.cama",
                               "direction": "left", "distance_mm": 100})

    for box in reg.store.boxes_of("suite_01.cama"):
        assert box["rgb"] == [26, 26, 28]


# -- honestidade -------------------------------------------------------------
def test_cor_desconhecida_recusa_antes_de_tocar_na_cena(reg):
    """O schema pega primeiro, e devolve o enum — o modelo se corrige sozinho."""
    res = reg.invoke("set_color", {"object_id": "suite_01.cama",
                                   "color": "azul-petroleo-fosco"})

    assert res["ok"] is False
    err = res["error"]
    assert err["code"] == "INVALID_ARGUMENTS"
    assert "preto" in err["schema"]["properties"]["color"]["enum"]
    assert reg.store.history() == []


def test_handler_tambem_recusa_cor_inventada(reg):
    """Defesa em profundidade: se alguem chamar o handler direto, ainda recusa."""
    with pytest.raises(colors.UnknownColor) as exc:
        reg._set_color(object_id="suite_01.cama", color="azul-petroleo-fosco")

    assert "preto" in exc.value.available
    assert reg.store.history() == []


def test_sinonimo_em_ingles_nao_e_barrado_pelo_schema(reg):
    """`black` e alias valido; barrar no schema mataria a tabela de sinonimos."""
    res = reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "black"})

    assert res["ok"] is True, res
    assert res["data"]["color"] == "preto"


def test_objeto_travado_nao_muda_de_cor(reg):
    reg.invoke("lock_object", {"object_id": "suite_01.cama"})

    res = reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})

    assert res["ok"] is False
    for box in reg.store.boxes_of("suite_01.cama"):
        assert box["rgb"] == [200, 200, 200]


def test_set_color_declara_contrato_certo_pro_modelo(tmp_path):
    reg = Registry(_cfg(tmp_path))
    spec = {t["name"]: t for t in reg.describe()["tools"]}["set_color"]

    assert spec["implemented"] is True
    assert spec["verification"] == "STATE_DELTA"
    assert spec["undoable"] is True
    assert spec["mutates"] is True
    # a lista de cores vai no SCHEMA: o modelo nao precisa adivinhar nem perguntar
    assert "preto" in spec["inputSchema"]["properties"]["color"]["enum"]


def test_gate_de_geometria_nao_roda_para_cor(reg):
    """Cor nao move nada. Rodar gate aqui seria teatro; fingir que rodou, mentira."""
    res = reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})

    assert "gates" not in res["data"]
    assert res["data"]["gatesRun"] is False


# -- a costura com o slice 2 -------------------------------------------------
def test_a_cor_chega_no_env_que_o_builder_le(tmp_path):
    """O teste que amarra as duas fatias.

    `set_color` pode pintar a cena e `apply_to_skp` pode rodar, e mesmo assim o
    `.skp` sair igual — se a cor não viajar no `LAYOUT_BOXES`. Aqui a prova é
    direta: o que o builder receberia carrega o rgb novo E um `mat_name` próprio
    (sem o nome próprio, `place_layout_skp.rb` reusa o material antigo).
    """
    import json

    from harness_caps.pipeline import Pipeline
    from tests.test_materialize import FakeRunner, _cfg as _mat_cfg

    cfg = _mat_cfg(tmp_path)
    reg = Registry(cfg)
    runner = FakeRunner()
    reg.pipe = Pipeline(cfg, runner=runner)
    reg.store.build(
        project="planta_74",
        rooms=[{"id": "r000", "name": "SUITE 01", "type": "BEDROOM"}],
        boxes=[_box(kind="bed", rgb=(200, 200, 200))],
        source={"builder": "teste"})

    reg.invoke("set_color", {"object_id": "suite_01.cama", "color": "preto"})
    res = reg.invoke("apply_to_skp", {})

    assert res["ok"] is True, res
    _cmd, env = runner.launched[0]
    enviado = json.loads(env["LAYOUT_BOXES"])
    assert enviado[0]["rgb"] == [26, 26, 28]
    assert enviado[0]["mat_name"].endswith("1a1a1c")
    # e depois de materializar, nao ha mais edicao pendente
    assert res["data"]["unmaterializedEdits"] == 0


def test_tool_desconhecida_sugere_a_parecida(tmp_path):
    """O modelo chuta `open_skp`; a real e `open_skp_in_sketchup`.

    Despejar as 35 tools nao ajuda — ele ignora a lista e chuta de novo. A
    sugestao dirigida e o que ele consegue usar. Pego rodando o app (2026-09-21).
    """
    reg = Registry(_cfg(tmp_path))

    res = reg.invoke("open_skp", {})

    assert res["ok"] is False
    err = res["error"]
    assert err["code"] == "UNKNOWN_TOOL"
    assert "open_skp_in_sketchup" in err["didYouMean"]
    assert "Você quis dizer" in err["message"]
