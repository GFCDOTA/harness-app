"""Capability host — processo filho que o Harness possui.

Protocolo: NDJSON em stdin/stdout. Uma linha JSON entra, uma linha JSON sai, na
mesma ordem. Sem HTTP, sem porta, sem daemon: o processo é FILHO do Harness e
morre com ele. Isso é a lição do NOC aplicada — nada sobrevive sozinho.

    {"id":"1","method":"describe"}
    {"id":"2","method":"invoke","tool":"find_object","args":{"query":"mesa"}}
    {"id":"3","method":"ping"}

Toda resposta carrega o `id` do pedido. Erro vira resposta tipada, nunca stack
trace no stdout — stdout é canal de dados; diagnóstico vai para stderr.

Rodar à mão:
    python -m harness_caps.host
"""
from __future__ import annotations

import json
import sys
import time
import traceback

from .config import load
from .registry import Registry


def serve(stdin=None, stdout=None, stderr=None) -> int:
    """Laço de atendimento. Injetável para teste — sem tocar em `sys` direto."""
    stdin = stdin or sys.stdin
    stdout = stdout or sys.stdout
    stderr = stderr or sys.stderr

    cfg = load()
    registry = Registry(cfg)
    _emit(stdout, {"id": "hello", "ok": True, "event": "ready",
                   "project": cfg.project, "toolCount": len(registry.describe()["tools"])})

    for line in stdin:
        line = line.strip()
        if not line:
            continue
        started = time.monotonic()
        try:
            req = json.loads(line)
        except ValueError as exc:
            _emit(stdout, {"id": None, "ok": False,
                           "error": {"code": "BAD_JSON", "message": str(exc)}})
            continue

        rid = req.get("id")
        method = req.get("method")
        try:
            if method == "ping":
                resp = {"ok": True, "data": {"pong": True}}
            elif method == "describe":
                resp = {"ok": True, "data": registry.describe()}
            elif method == "invoke":
                resp = registry.invoke(req.get("tool"), req.get("args"))
            elif method == "shutdown":
                _emit(stdout, {"id": rid, "ok": True, "data": {"bye": True}})
                return 0
            else:
                resp = {"ok": False, "error": {"code": "UNKNOWN_METHOD",
                                               "message": f"método '{method}' desconhecido"}}
        except Exception as exc:  # noqa: BLE001 — o host não cai por causa de um pedido
            traceback.print_exc(file=stderr)
            resp = {"ok": False, "error": {"code": "HOST_ERROR",
                                           "message": f"{type(exc).__name__}: {exc}"}}

        resp["id"] = rid
        resp["elapsedMs"] = round((time.monotonic() - started) * 1000, 1)
        _emit(stdout, resp)
    return 0


def _emit(stream, payload: dict) -> None:
    stream.write(json.dumps(payload, ensure_ascii=False) + "\n")
    stream.flush()


if __name__ == "__main__":
    sys.exit(serve())
