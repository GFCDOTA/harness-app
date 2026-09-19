"""Execução dos GATES determinísticos sobre a cena EDITADA.

A autoridade sobre "a alteração é válida?" é daqui — não do modelo. O agente pode
dizer que moveu; só o gate diz se ficou de pé.

Detalhe que importa: `overlap_gate(con, room)` e `sanity_room(con, room)` do
pipeline RECOMPUTAM a mobília pelo cérebro e por isso ignorariam a edição. Este
módulo chama os NÚCLEOS PUROS dos mesmos gates, passando os boxes que estão de
fato na cena. Mesmo cálculo, entrada correta.
"""
from __future__ import annotations

from .pipeline import Pipeline, PipelineUnavailable

#: ordem de severidade — o pior resultado manda no veredito geral.
_RANK = {"PASS": 0, "WARN": 1, "INCOMPLETE": 2, "FAIL": 3, "UNAVAILABLE": 4}


def _worst(results: list[str]) -> str:
    return max(results, key=lambda r: _RANK.get(r, 0)) if results else "INCOMPLETE"


def run_room_gates(pipe: Pipeline, con: dict, boxes: list[dict], room_id: str,
                   room_boxes: list[dict]) -> dict:
    """Roda os três gates que julgam mobília num cômodo.

    - `circulation`  : as faixas livres continuam passando? (P1 do VERDICT)
    - `overlap`      : móvel sobre móvel?
    - `geometry`     : bbox degenerada, fora do cômodo, enterrada, escala explodida

    Gate indisponível vira `UNAVAILABLE` com o motivo — nunca um PASS por omissão.
    """
    gates: dict[str, dict] = {}

    try:
        gates["circulation"] = _norm(pipe.circulation_gate(con, boxes, room_id))
    except PipelineUnavailable as exc:
        gates["circulation"] = {"result": "UNAVAILABLE", "detail": exc.why}
    except Exception as exc:  # noqa: BLE001 — gate que explode é INCOMPLETE, não PASS
        gates["circulation"] = {"result": "INCOMPLETE", "detail": f"{type(exc).__name__}: {exc}"}

    try:
        gates["overlap"] = _norm(pipe.overlap_gate_on(room_boxes))
    except PipelineUnavailable as exc:
        gates["overlap"] = {"result": "UNAVAILABLE", "detail": exc.why}
    except Exception as exc:  # noqa: BLE001
        gates["overlap"] = {"result": "INCOMPLETE", "detail": f"{type(exc).__name__}: {exc}"}

    try:
        poly = pipe.room_polygon_in(con, room_id)
        raw = pipe.geometry_sanity_on(room_boxes, [poly] if poly else None)
        gates["geometry"] = {
            "result": raw.get("overall", "INCOMPLETE"),
            "nParts": raw.get("n_parts"),
            "fails": [f"{f['check']}: {f.get('label') or f.get('kind')} — {f['detail']}"
                      for f in raw.get("findings", []) if f["severity"] == "FAIL"],
            "warns": [f"{f['check']}: {f.get('label') or f.get('kind')} — {f['detail']}"
                      for f in raw.get("findings", []) if f["severity"] == "WARN"],
            "findingTypes": sorted({f["check"] for f in raw.get("findings", [])}),
            "roomPolygon": bool(poly),
        }
    except PipelineUnavailable as exc:
        gates["geometry"] = {"result": "UNAVAILABLE", "detail": exc.why}
    except Exception as exc:  # noqa: BLE001
        gates["geometry"] = {"result": "INCOMPLETE", "detail": f"{type(exc).__name__}: {exc}"}

    overall = _worst([g["result"] for g in gates.values()])
    return {
        "roomId": room_id,
        "overall": overall,
        "clean": overall in ("PASS", "WARN"),
        "gates": gates,
        "findings": collect_findings(pipe, gates),
    }


def _norm(raw: dict) -> dict:
    """Formata a saída de um gate sem perder o que ele disse — nem o PORQUÊ.

    O `circulation_gate` não usa `fails`/`warns`: ele devolve `checks` aninhado,
    com o estado de cada portal. Sem achatar isso, um FAIL chegava na tela sem
    uma linha de motivo — que é exatamente o "500 Internal Server Error" que a
    missão (§44) proíbe. Aqui o motivo é extraído, não descartado.
    """
    fails = list(raw.get("fails", []))
    warns = list(raw.get("warns", []))
    f2, w2 = _flatten_checks(raw.get("checks") or {})
    return {
        "result": raw.get("result", "INCOMPLETE"),
        "fails": fails + f2,
        "warns": warns + w2,
        "checks": raw.get("checks"),
        **{k: v for k, v in raw.items()
           if k not in ("result", "fails", "warns", "checks")},
    }


def _flatten_checks(checks: dict) -> tuple[list[str], list[str]]:
    """Transforma o `checks` aninhado em frases legíveis, preservando os números."""
    fails: list[str] = []
    warns: list[str] = []
    for name, data in checks.items():
        if not isinstance(data, dict):
            continue
        result = str(data.get("result", ""))
        for portal in data.get("portais", []) or []:
            status = str(portal.get("status", ""))
            if status in ("", "PASS"):
                continue
            empty, furnished = portal.get("w_empty_m"), portal.get("w_furnished_m")
            medida = (f"faixa livre {furnished:.2f}m (vazio: {empty:.2f}m)"
                      if isinstance(furnished, (int, float)) and isinstance(empty, (int, float))
                      else "faixa não medida")
            msg = (f"{name}: portal {portal.get('portal_role', '?')} em "
                   f"{_pt(portal.get('portal'))} — {status}, {medida}")
            (warns if status.startswith("WARN") else fails).append(msg)
        if result == "FAIL" and not any(name in m for m in fails):
            fails.append(f"{name}: FAIL {_reason(data)}")
        elif result == "WARN" and not any(name in m for m in warns):
            warns.append(f"{name}: WARN {_reason(data)}")
    return fails, warns


def _pt(p) -> str:
    if isinstance(p, (list, tuple)) and len(p) >= 2:
        return f"({p[0]:.0f},{p[1]:.0f})"
    return "?"


def _reason(data: dict) -> str:
    """Números declarados pelo próprio gate — nunca uma explicação inventada."""
    bits = [f"{k}={v}" for k, v in data.items()
            if k != "result" and isinstance(v, (int, float, str))]
    return "(" + ", ".join(bits[:4]) + ")" if bits else ""


def collect_findings(pipe: Pipeline, gates: dict) -> list[dict]:
    """Transforma falhas de gate em findings ROTEADOS pelo router real (FP-033).

    A rota decide QUEM age: o loop determinístico, o olho visual, o agente ou o
    Felipe. Quem classifica é o pipeline, não este módulo e muito menos o modelo.
    """
    out: list[dict] = []
    for gate_name, data in gates.items():
        for kind, severity in (("fails", "FAIL"), ("warns", "WARN")):
            for msg in data.get(kind, []):
                ftype = _finding_type(gate_name, msg, data)
                try:
                    route = pipe.route_finding(ftype)
                except PipelineUnavailable:
                    route = "NEEDS_FELIPE"
                out.append({"gate": gate_name, "type": ftype, "severity": severity,
                            "detail": msg, "route": route})
    return out


def _finding_type(gate_name: str, message: str, data: dict) -> str:
    """Nome de finding que o router conhece. Desconhecido roteia para o Felipe."""
    if gate_name == "overlap":
        return "furniture_overlap"
    if gate_name == "circulation":
        return "blocks_door" if "porta" in message.lower() else "circulation"
    head = message.split(":", 1)[0].strip()
    known = set(data.get("findingTypes") or ())
    return head if head in known else (head or "global_visual")
