"""Testes do slice 2 — `apply_to_skp`: a cena editada vira `.skp`.

O que estes testes travam, e por que cada um existe:

- **A cena manda, não o cérebro.** O ponto da fatia é a edição do Felipe chegar
  no arquivo. Se `materialize` recomputasse os boxes pelo cérebro de layout, o
  `.skp` sairia igual ao de sempre e a fatia inteira seria teatro.
- **`.skp` de 0 byte nunca é sucesso.** É o sintoma clássico de falha do
  SketchUp em lote (o `.skb` de 0 B). Sem esta trava o sistema aprende a mentir.
- **Timeout é falha declarada**, não host travado.
- **O canônico não é destino.** Sobrescrever o `.skp` que o Felipe abre, sem
  snapshot, é perda irreversível.
- **SketchUp aberto é recusa, não taskkill silencioso.** `furnish_apartment` dá
  `taskkill /F /IM SketchUp.exe`; rodar isso por conta própria mata a janela que
  o Felipe está olhando. Fechar só sob autorização explícita.

Tudo roda sem SketchUp: `FakeRunner` é o dublê de processo e de relógio.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from harness_caps.config import HarnessConfig
from harness_caps.pipeline import Pipeline, SketchUpBusy


def _cfg(tmp_path) -> HarnessConfig:
    repo = tmp_path / "sketchup-mcp"
    (repo / "tools").mkdir(parents=True)
    (repo / "tools" / "place_layout_skp.rb").write_text("# dublê", "utf-8")
    art = repo / "artifacts" / "planta_74"
    art.mkdir(parents=True)
    (art / "planta_74.skp").write_bytes(b"shell")
    exe = tmp_path / "SketchUp.exe"
    exe.write_bytes(b"exe")
    return HarnessConfig(
        pipeline_repo=repo,
        state_dir=tmp_path / "state",
        project="planta_74",
        consensus_path=None,
        pt_to_m="0.0259",
        sketchup_exe=str(exe),
    )


def _box(module="Escrivaninha", x0=100.0):
    return {"room": "SUITE 02", "module": module, "kind": "desk", "label": module,
            "x0": x0, "y0": 200.0, "x1": x0 + 40, "y1": 220.0,
            "z0_in": 0.0, "h_in": 29.0}


class FakeRunner:
    """Dublê do SketchUp: processo, relógio e o que o builder escreveria.

    `produce` decide o que a "execução" deixa no disco: `None` = nada (timeout),
    `b""` = arquivo vazio (a falha clássica), bytes = arquivo bom.
    """

    def __init__(self, *, running=False, produce=b"skp-bom", write_log=True):
        self.running = running
        self.produce = produce
        self.write_log = write_log
        self.killed = 0
        self.launched: list[tuple[list[str], dict]] = []
        self._t = 1000.0

    # -- processo -----------------------------------------------------------
    def is_running(self) -> bool:
        return self.running

    def kill(self) -> None:
        self.killed += 1
        self.running = False

    def launch(self, cmd, env) -> None:
        from pathlib import Path
        self.launched.append((list(cmd), dict(env)))
        if self.produce is not None:
            Path(env["LAYOUT_OUT"]).write_bytes(self.produce)
        if self.write_log:
            Path(env["LAYOUT_LOG"]).write_text("ok", "utf-8")

    # -- relógio ------------------------------------------------------------
    def now(self) -> float:
        return self._t

    def sleep(self, seconds: float) -> None:
        self._t += max(seconds, 0.5)


def _pipe(tmp_path, runner):
    return Pipeline(_cfg(tmp_path), runner=runner)


# -- o ponto da fatia --------------------------------------------------------
def test_boxes_da_cena_chegam_no_env_e_nao_os_do_cerebro(tmp_path):
    runner = FakeRunner()
    editado = [_box(x0=999.0)]

    _pipe(tmp_path, runner).materialize(editado)

    _cmd, env = runner.launched[0]
    assert json.loads(env["LAYOUT_BOXES"]) == editado


def test_comando_passa_o_skp_base_antes_do_rubystartup(tmp_path):
    """Ordem invertida faz o SketchUp ignorar o arquivo — gotcha pago antes."""
    runner = FakeRunner()

    _pipe(tmp_path, runner).materialize([_box()])

    cmd, _env = runner.launched[0]
    assert cmd[1].endswith("planta_74.skp")
    assert cmd[2] == "-RubyStartup"
    assert cmd[3].endswith("place_layout_skp.rb")


def test_resultado_traz_a_evidencia_do_artefato(tmp_path):
    out = _pipe(tmp_path, FakeRunner()).materialize([_box()])

    assert out["verified"] is True
    assert out["sizeBytes"] == len(b"skp-bom")
    assert out["path"].endswith(".skp")


# -- honestidade -------------------------------------------------------------
def test_skp_de_zero_byte_nunca_e_sucesso(tmp_path):
    out = _pipe(tmp_path, FakeRunner(produce=b"")).materialize([_box()])

    assert out["verified"] is False
    assert "0 byte" in out["reason"] or "vazio" in out["reason"]


def test_arquivo_que_nao_foi_reescrito_nao_conta_como_sucesso(tmp_path):
    """Builder que falha em silêncio deixa o arquivo velho no lugar."""
    runner = FakeRunner(produce=None)  # não escreve nada
    pipe = _pipe(tmp_path, runner)
    destino = pipe.materialize_path()
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_bytes(b"artefato-velho")

    out = pipe.materialize([_box()], out_path=destino)

    assert out["verified"] is False


def test_timeout_do_sketchup_vira_falha_declarada(tmp_path):
    runner = FakeRunner(produce=None, write_log=False)

    out = _pipe(tmp_path, runner).materialize([_box()], timeout_sec=10)

    assert out["verified"] is False
    assert "timeout" in out["reason"].lower()


def test_boxes_vazios_nao_sobem_o_sketchup(tmp_path):
    runner = FakeRunner()

    out = _pipe(tmp_path, runner).materialize([])

    assert out["verified"] is False
    assert runner.launched == []


# -- o canônico é intocável --------------------------------------------------
def test_destino_default_nunca_e_o_skp_canonico(tmp_path):
    cfg = _cfg(tmp_path)
    canonico = cfg.pipeline_repo / "artifacts" / "planta_74" / "planta_74.skp"

    destino = Pipeline(cfg, runner=FakeRunner()).materialize_path()

    assert destino != canonico
    assert cfg.pipeline_repo not in destino.parents


def test_recusa_escrever_dentro_do_repo_do_pipeline(tmp_path):
    cfg = _cfg(tmp_path)
    alvo = cfg.pipeline_repo / "artifacts" / "planta_74" / "planta_74.skp"
    runner = FakeRunner()

    out = Pipeline(cfg, runner=runner).materialize([_box()], out_path=alvo)

    assert out["verified"] is False
    assert runner.launched == []


# -- a janela do Felipe ------------------------------------------------------
def test_recusa_quando_o_sketchup_esta_aberto(tmp_path):
    runner = FakeRunner(running=True)

    with pytest.raises(SketchUpBusy):
        _pipe(tmp_path, runner).materialize([_box()])

    assert runner.killed == 0
    assert runner.launched == []


def test_fecha_o_sketchup_so_sob_autorizacao_explicita(tmp_path):
    runner = FakeRunner(running=True)

    out = _pipe(tmp_path, runner).materialize([_box()], close_sketchup=True)

    assert runner.killed >= 1
    assert out["verified"] is True


# -- o contrato que o modelo enxerga ----------------------------------------
def _registry(tmp_path, runner):
    from harness_caps.registry import Registry
    reg = Registry(_cfg(tmp_path))
    reg.pipe = Pipeline(reg.cfg, runner=runner)
    reg.store.build(
        project="planta_74",
        rooms=[{"id": "r003", "name": "SUITE 02", "type": "BEDROOM"}],
        boxes=[_box()],
        source={"builder": "teste"})
    return reg


def test_apply_to_skp_deixou_de_ser_capability_recusada(tmp_path):
    from harness_caps.registry import UNSUPPORTED
    reg = _registry(tmp_path, FakeRunner())

    assert "apply_to_skp" not in UNSUPPORTED
    spec = {t["name"]: t for t in reg.describe()["tools"]}["apply_to_skp"]
    assert spec["implemented"] is True
    assert spec["verification"] == "ARTIFACT"
    assert spec["risk"] == "MEDIUM"
    assert spec["mutates"] is True


def test_sketchup_aberto_vira_erro_tipado_e_nao_handler_error(tmp_path):
    reg = _registry(tmp_path, FakeRunner(running=True))

    res = reg.invoke("apply_to_skp", {})

    assert res["ok"] is False
    err = res["error"]
    assert err["code"] == "SKETCHUP_BUSY"
    assert err["needsHumanDecision"] is True
    assert err["changesApplied"] is False


def test_materializar_zera_as_edicoes_pendentes(tmp_path):
    reg = _registry(tmp_path, FakeRunner())
    obj = reg.store.objects()[0]
    reg.store.translate(obj.id, 1.0, 0.0, 0.0, reason="teste")
    assert reg.store.unmaterialized_edits() == 1

    res = reg.invoke("apply_to_skp", {})

    assert res["ok"] is True
    assert res["data"]["verified"] is True
    assert res["data"]["unmaterializedEdits"] == 0


def test_edicao_depois_de_materializar_volta_a_ficar_pendente(tmp_path):
    reg = _registry(tmp_path, FakeRunner())
    obj = reg.store.objects()[0]
    reg.invoke("apply_to_skp", {})

    reg.store.translate(obj.id, 5.0, 0.0, 0.0, reason="depois")

    assert reg.store.unmaterialized_edits() == 1
    assert reg.invoke("get_project_state", {})["data"]["unmaterializedEdits"] == 1


def test_skp_materializado_pelo_harness_aparece_na_lista(tmp_path):
    """Gap pego rodando o app: o Harness gerava um .skp que ele nao abria.

    `skp_artifacts()` varria so `artifacts/<projeto>` do repo do pipeline. Como o
    slice 2 escreve FORA desse repo de proposito, a saida do proprio Harness
    ficava invisivel para `open_skp_in_sketchup` — o modelo achava a tool certa e
    nao achava o arquivo.
    """
    cfg = _cfg(tmp_path)
    pipe = Pipeline(cfg, runner=FakeRunner())

    out = pipe.materialize([_box()])
    assert out["verified"] is True

    nomes = {a["name"]: a for a in pipe.skp_artifacts()}
    gerado = Path(out["path"]).name
    assert gerado in nomes, f"o .skp que o Harness acabou de gerar sumiu da lista: {list(nomes)}"
    assert nomes[gerado]["source"] == "harness"
