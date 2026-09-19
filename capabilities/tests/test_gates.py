"""Testes da camada de gates — sem pipeline, com dublês.

A regra que estes testes travam: um gate nunca vira PASS por omissão, e um FAIL
nunca chega sem motivo legível.
"""
from __future__ import annotations

from harness_caps import gates as g
from harness_caps.pipeline import PipelineUnavailable


class FakePipe:
    """Dublê do pipeline. Cada atributo diz o que aquele gate vai responder."""

    def __init__(self, circulation=None, overlap=None, geometry=None,
                 poly=None, route="DETERMINISTIC_AUTOFIX"):
        self._circ, self._over, self._geo = circulation, overlap, geometry
        self._poly, self._route = poly, route

    def _answer(self, value):
        if isinstance(value, Exception):
            raise value
        return value

    def circulation_gate(self, con, boxes, room_id):
        return self._answer(self._circ or {"result": "PASS"})

    def overlap_gate_on(self, boxes):
        return self._answer(self._over or {"result": "PASS", "fails": [], "warns": []})

    def geometry_sanity_on(self, boxes, rooms):
        return self._answer(self._geo or {"overall": "PASS", "n_parts": len(boxes),
                                          "findings": []})

    def room_polygon_in(self, con, room_id):
        return self._poly

    def route_finding(self, ftype, axis=None):
        return self._route


def _run(pipe, boxes=None):
    boxes = boxes or [{"kind": "desk"}]
    return g.run_room_gates(pipe, {"walls": []}, boxes, "r000", boxes)


def test_tudo_passando_fica_clean():
    out = _run(FakePipe())
    assert out["overall"] == "PASS" and out["clean"] is True


def test_o_pior_gate_manda_no_veredito():
    out = _run(FakePipe(overlap={"result": "FAIL", "fails": ["a x b"], "warns": []}))
    assert out["overall"] == "FAIL" and out["clean"] is False


def test_warn_ainda_e_clean_mas_aparece():
    out = _run(FakePipe(overlap={"result": "WARN", "fails": [], "warns": ["rocou"]}))
    assert out["overall"] == "WARN" and out["clean"] is True
    assert out["findings"][0]["severity"] == "WARN"


def test_gate_indisponivel_nao_vira_pass():
    out = _run(FakePipe(circulation=PipelineUnavailable("shapely", "não instalado")))
    assert out["gates"]["circulation"]["result"] == "UNAVAILABLE"
    assert out["overall"] == "UNAVAILABLE"
    assert "não instalado" in out["gates"]["circulation"]["detail"]


def test_gate_que_explode_vira_incomplete_com_o_erro_real():
    out = _run(FakePipe(overlap=ValueError("shapely bugou")))
    assert out["gates"]["overlap"]["result"] == "INCOMPLETE"
    assert "shapely bugou" in out["gates"]["overlap"]["detail"]


def test_fail_de_circulacao_chega_com_o_motivo_e_os_numeros():
    """Regressão: o gate de circulação não usa fails/warns, usa `checks`.

    Sem achatar, um FAIL chegava na tela SEM uma linha de motivo.
    """
    out = _run(FakePipe(circulation={
        "result": "FAIL", "room": "SUITE 01",
        "checks": {"corredor_principal": {
            "result": "FAIL",
            "portais": [{"portal": [357.1, 619.0], "portal_role": "PRIMARY",
                         "status": "FAIL_FURNITURE_BLOCKING_CIRCULATION",
                         "w_empty_m": 1.0, "w_furnished_m": 0.0}]}}}))
    fails = out["gates"]["circulation"]["fails"]
    assert fails, "FAIL sem motivo é o '500 Internal Server Error' proibido"
    assert "PRIMARY" in fails[0] and "0.00m" in fails[0] and "1.00m" in fails[0]


def test_checks_aninhado_sem_portais_ainda_explica():
    out = _run(FakePipe(circulation={
        "result": "FAIL",
        "checks": {"cadeira_puxada": {"result": "FAIL", "recuo_m": 0.5}}}))
    assert "cadeira_puxada" in out["gates"]["circulation"]["fails"][0]
    assert "recuo_m=0.5" in out["gates"]["circulation"]["fails"][0]


def test_findings_de_geometria_carregam_o_tipo_que_o_router_conhece():
    out = _run(FakePipe(geometry={
        "overall": "FAIL", "n_parts": 1,
        "findings": [{"severity": "FAIL", "check": "outside_room",
                      "label": "Escrivaninha", "kind": "desk",
                      "detail": "centro (10,10) fora"}]}))
    f = [x for x in out["findings"] if x["gate"] == "geometry"][0]
    assert f["type"] == "outside_room"
    assert f["route"] == "DETERMINISTIC_AUTOFIX"


def test_colisao_sempre_roteia_como_furniture_overlap():
    out = _run(FakePipe(overlap={"result": "FAIL", "fails": ["Mesa x Sofa: 50%"],
                                 "warns": []}))
    assert out["findings"][0]["type"] == "furniture_overlap"


def test_router_indisponivel_manda_para_o_felipe_em_vez_de_adivinhar():
    class SemRouter(FakePipe):
        def route_finding(self, ftype, axis=None):
            raise PipelineUnavailable("finding_router", "módulo ausente")

    out = _run(SemRouter(overlap={"result": "FAIL", "fails": ["x"], "warns": []}))
    assert out["findings"][0]["route"] == "NEEDS_FELIPE"
