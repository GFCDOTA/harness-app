"""Configuração CENTRAL do capability host.

Regra do Master Orchestrator (CLAUDE.md §configuração): caminho de máquina,
porta, modelo e política NÃO ficam espalhados por handler. Ficam aqui, resolvidos
numa ordem só: variável de ambiente > arquivo `harness.json` > convenção.

Este módulo não importa o pipeline. Ele só diz ONDE as coisas estão — quem tenta
importar é `pipeline.py`, que degrada com honestidade quando o repo não está lá.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

# raiz do repo harness-app (capabilities/harness_caps/config.py -> ../../)
HARNESS_ROOT = Path(__file__).resolve().parents[2]

_CONFIG_FILENAME = "harness.json"


def _load_file_config() -> dict:
    """`harness.json` na raiz do harness-app. Ausente = {} (a convenção resolve)."""
    p = HARNESS_ROOT / _CONFIG_FILENAME
    if not p.exists():
        return {}
    try:
        data = json.loads(p.read_text("utf-8"))
    except (ValueError, OSError):
        return {}
    return data if isinstance(data, dict) else {}


_FILE = _load_file_config()


def _resolve(env_key: str, file_key: str, default):
    """env > harness.json > convenção. String vazia conta como ausente."""
    v = os.environ.get(env_key)
    if v is not None and v.strip():
        return v.strip()
    v = _FILE.get(file_key)
    if isinstance(v, str) and v.strip():
        return v.strip()
    if v is not None and not isinstance(v, str):
        return v
    return default


@dataclass(frozen=True)
class HarnessConfig:
    """O que o host precisa saber para falar com o mundo real."""

    pipeline_repo: Path
    state_dir: Path
    project: str
    consensus_path: Path | None
    pt_to_m: str
    max_agent_attempts: int = 4
    gate_timeout_sec: int = 180
    #: risco que o host NUNCA executa sozinho — precisa de confirmação humana.
    confirm_risks: tuple[str, ...] = field(default_factory=lambda: ("HIGH",))

    @property
    def scene_path(self) -> Path:
        return self.state_dir / f"{self.project}.scene.json"

    @property
    def snapshot_dir(self) -> Path:
        return self.state_dir / "snapshots" / self.project

    def to_dict(self) -> dict:
        return {
            "pipelineRepo": str(self.pipeline_repo),
            "stateDir": str(self.state_dir),
            "project": self.project,
            "consensusPath": str(self.consensus_path) if self.consensus_path else None,
            "ptToM": self.pt_to_m,
            "maxAgentAttempts": self.max_agent_attempts,
            "confirmRisks": list(self.confirm_risks),
            "scenePath": str(self.scene_path),
            "snapshotDir": str(self.snapshot_dir),
        }


def load() -> HarnessConfig:
    """Resolve a configuração efetiva desta máquina."""
    repo = Path(str(_resolve("HARNESS_PIPELINE_REPO", "pipelineRepo",
                             HARNESS_ROOT.parent / "sketchup-mcp")))
    state = Path(str(_resolve("HARNESS_STATE_DIR", "stateDir",
                              HARNESS_ROOT / "state-local")))
    project = str(_resolve("HARNESS_PROJECT", "project", "planta_74"))

    consensus_raw = _resolve("HARNESS_CONSENSUS", "consensusPath", None)
    if consensus_raw:
        consensus = Path(str(consensus_raw))
    else:
        # convenção do pipeline: fixtures/<project>/consensus_*.json
        guess = repo / "fixtures" / project / "consensus_with_human_walls_and_soft_barriers.json"
        consensus = guess if guess.exists() else None

    # PT_TO_M é gotcha CONGELADO do projeto (memória: planta_74 = 0.0259) e precisa
    # estar no ambiente ANTES de core.scale ser importado. Ver pipeline.py.
    pt_to_m = str(_resolve("PT_TO_M", "ptToM", "0.0259"))

    attempts = _resolve("HARNESS_MAX_AGENT_ATTEMPTS", "maxAgentAttempts", 4)
    try:
        attempts = int(attempts)
    except (TypeError, ValueError):
        attempts = 4

    return HarnessConfig(
        pipeline_repo=repo,
        state_dir=state,
        project=project,
        consensus_path=consensus,
        pt_to_m=pt_to_m,
        max_agent_attempts=attempts,
    )
