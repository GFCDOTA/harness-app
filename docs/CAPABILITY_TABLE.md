# Tabela de capabilities — GERADA, não escrita

> ⚠️ **Não editar à mão.** Saída de `tools/capability_table.py`, lida do
> registry em `capabilities/harness_caps/registry.py`. Se esta tabela
> discorda do runtime, o runtime está certo e o gerador é que quebrou.

**36 capabilities publicadas** — 25 IMPLEMENTED · 11 UNAVAILABLE

`PARTIAL` não aparece aqui: é julgamento humano sobre qualidade, não
estado que o runtime saiba afirmar. Vive na prosa da
[`CAPABILITY_MATRIX.md`](CAPABILITY_MATRIX.md), não numa contagem.

## IMPLEMENTED

| capability | muda estado | reversível | verificação | risco | exige |
|---|---|---|---|---|---|
| `apply_to_skp` | sim | — | ARTIFACT | MEDIUM | pipeline, SketchUp, scene |
| `find_object` | leitura | — | NONE | LOW | — |
| `get_agent_info` | leitura | — | NONE | LOW | — |
| `get_findings` | leitura | — | NONE | LOW | pipeline, scene |
| `get_object` | leitura | — | NONE | LOW | — |
| `get_project_state` | leitura | — | NONE | LOW | — |
| `get_system_status` | leitura | — | NONE | LOW | — |
| `list_history` | leitura | — | NONE | LOW | — |
| `list_locked_objects` | leitura | — | NONE | LOW | — |
| `list_objects` | leitura | — | NONE | LOW | — |
| `list_rooms` | leitura | — | NONE | LOW | — |
| `list_skp_artifacts` | leitura | — | NONE | LOW | pipeline |
| `list_snapshots` | leitura | — | NONE | LOW | — |
| `lock_object` | sim | — | NONE | LOW | scene |
| `move_object` | sim | sim | STATE_DELTA | LOW | scene |
| `open_project` | sim | — | NONE | MEDIUM | pipeline |
| `open_skp_in_sketchup` | leitura | — | NONE | MEDIUM | pipeline, SketchUp |
| `redo` | sim | sim | NONE | LOW | scene |
| `restore_last_clean` | sim | — | NONE | MEDIUM | scene |
| `restore_snapshot` | sim | — | NONE | MEDIUM | scene |
| `run_gates` | leitura | — | NONE | LOW | pipeline, scene |
| `save_snapshot` | sim | — | NONE | LOW | scene |
| `set_color` | sim | sim | STATE_DELTA | MEDIUM | scene |
| `undo` | sim | sim | NONE | LOW | scene |
| `unlock_object` | sim | — | NONE | LOW | scene |

## UNAVAILABLE — registrada, recusa com motivo

Estas existem no vocabulário de propósito: o modelo escolhe tool por
nome e ignora proibição em prosa. Chamar devolve o motivo.

| capability | por que ainda não existe |
|---|---|
| `create_contact_sheet` | depende de render; slice 5 |
| `create_object` | criação é do cérebro de layout; a tool viria de furniture_class |
| `delete_object` | remoção muda o programa do cômodo — HIGH, e ainda sem caminho reversível |
| `render` | render sobe o SketchUp em lote (não é serviço vivo); slice 5 |
| `render_room` | idem render |
| `rotate_object` | o pipeline gera peças eixo-alinhadas; girar exige regenerar pelo builder, não transladar |
| `run_correction_loop` | tools/correction_loop opera sobre consensus/SKP, não sobre a cena editada ainda |
| `scale_object` | dimensão vem da classe paramétrica de móvel (derive_spec), não de escala livre |
| `set_camera` | depende do SketchUp em lote; slice 5 |
| `set_material` | TEXTURA é outra coisa que cor: vive em style_spec + LAYOUT_TEX_MAP e precisa de PNG por kind. Para mudar a COR de um objeto use `set_color` |
| `visual_review` | depende de render + juiz visual; slice 5 |

