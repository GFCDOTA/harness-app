# Capability Matrix — a ANÁLISE. A contagem está em outro lugar.

**Levantado em:** 2026-09-19 · **contagem movida em:** 2026-09-27

## ⚠️ Este documento NÃO é a fonte da verdade sobre o que existe

A lista de capabilities e seus estados vivem em
[**`CAPABILITY_TABLE.md`**](CAPABILITY_TABLE.md), **gerada do registry** por
`tools/capability_table.py` e travada por
`capabilities/tests/test_capability_table.py`.

Por que a mudança: esta matriz era mantida à mão em paralelo ao registry e as duas
derivaram. Em 2026-09-27 o diagnóstico era **40 linhas aqui contra 36 tools no
runtime**, com **17 nomes sem tool nenhuma** e **13 tools sem linha** — incluindo
entradas marcadas READY para capability que não existe com aquele nome
(`get_scene`, `save_project`, `snapshot`, `status` são, no runtime,
`get_project_state`, `save_snapshot`, `save_snapshot` e `get_system_status`).

Num app cujo propósito é **ensinar** como o sistema funciona, documento que diz
READY para coisa inexistente é o pior defeito possível. Duas fontes de verdade
sempre viram uma verdade e uma mentira.

**O que sobrou aqui, e por que vale:** a ANÁLISE — a ressalva por capability, o
que falta em cada PARTIAL, a evidência de código, o histórico das fatias. Isso é
julgamento humano e o registry não carrega. As tabelas abaixo continuam úteis como
leitura; os **status individuais podem estar velhos** — confira na tabela gerada.

## Legenda (histórica, para ler as tabelas abaixo)

| Status | Significado |
|---|---|
| **READY** | chamável pelo Harness, executa, efeito observável |
| **PARTIAL** | executa, mas com ressalva relevante (escopo, verificação, alcance) |
| **MISSING** | não existe caminho; hoje é tool que recusa com motivo |

`PARTIAL` é **julgamento humano** e por isso não aparece na tabela gerada: o
runtime sabe dizer `implemented` true/false, não "executa com ressalva".

**Verified?** = existe checagem de que a ação *ocorreu*, além de o handler não ter
lançado. Hoje a resposta honesta é quase sempre *não* — ver
[revisão B2](review/SLICE1_ARCHITECTURAL_REVIEW.md).

---

## ⚠️ Ressalva que atravessa a matriz inteira — RESOLVIDA no slice 2

Até 2026-09-20 valia: *"toda capability de escrita opera o documento de cena, não
o `.skp`; `apply_to_skp` é MISSING, e o arquivo que o Felipe abre continua o de
2026-08-09."*

**Deixou de valer.** `apply_to_skp` está READY: a cena editada vira um `.skp`
novo, por execução do SketchUp em lote sobre os boxes do `SceneStore`.

O que continua verdadeiro, e importa ao ler a coluna "efeito": a escrita ainda é
em **dois tempos**. `move_object` muda a cena; o `.skp` só acompanha quando
`apply_to_skp` roda. `get_project_state.unmaterializedEdits` diz quantas edições
ainda não chegaram no arquivo — é a métrica honesta dessa defasagem.

---

## Ciclo de vida de projeto e aplicação

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `open_sketchup` | **PARTIAL** | `pipeline.open_in_sketchup()` via `subprocess.Popen` desanexado | `open_skp_in_sketchup` | ❌ retorna na linha seguinte ao Popen | n/a | ❌ | checar processo vivo; hoje abre um `.skp`, não "o SketchUp" |
| `close_sketchup` | **MISSING** | — | — | — | — | — | tudo. `furnish_apartment` usa `taskkill /F /IM SketchUp.exe` — padrão existe, não exposto |
| `open_project` | **READY** | `Pipeline.collect_boxes` + `SceneStore.build` | `open_project` | ❌ | ❌ (reconstrói) | ❌ | ~40 s no primeiro build; `rebuild=true` DESCARTA edições sem confirmar |
| `save_project` | **PARTIAL** | `SceneStore.save()` — toda mutação já persiste | implícito | ✅ atômico (`.tmp` + replace) | n/a | ❌ | não existe "salvar como"; nada grava `.skp` |
| `apply_to_skp` | **READY** | `Pipeline.materialize()` — SketchUp em lote sobre `SceneStore.boxes()` | `apply_to_skp` | ✅ `ARTIFACT`: existe, >0 byte, escrito por esta execução | ❌ o arquivo já foi escrito | ❌ não muda geometria | destino fora do repo do pipeline; SketchUp aberto → `SKETCHUP_BUSY`, fechar só com `close_sketchup=true` |

## Leitura de cena

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `get_scene` | **READY** | `SceneStore.boxes()` = baseline + edições | `get_project_state` | n/a | n/a | n/a | — |
| `get_room` | **PARTIAL** | `SceneStore.boxes_in_room()` | `list_objects(room_id)` | n/a | n/a | n/a | não devolve polígono/portais do cômodo |
| `list_objects` | **READY** | agrupamento por `module` | `list_objects` | n/a | n/a | n/a | — |
| `find_object` | **READY** | `SceneStore.find` — exato → substring → token, com stopwords PT | `find_object` | n/a | n/a | n/a | não usa cômodo ativo para desempatar ([M2](review/SLICE1_ARCHITECTURAL_REVIEW.md)) |
| `get_object` | **READY** | `SceneObject.to_dict()` com bbox em m | `get_object` | n/a | n/a | n/a | — |
| `get_selection` | **MISSING** | — | — | — | — | — | não há seleção; `last_referenced_object` faz as vezes |

## Transformação

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `move_object` | **PARTIAL** | `scene.translate_box` — extremos, `corners`, `profile_world`; `extrude_vec` não (é direção) | `move_object` | ⚠️ só que o store aceitou | ✅ exato | ✅ automático | só a cena; gates só do cômodo de origem ([H2](review/SLICE1_ARCHITECTURAL_REVIEW.md)) |
| `rotate_object` | **MISSING** | — | tool que recusa | — | — | — | peças são eixo-alinhadas; girar exige regenerar pelo builder |
| `resize_object` | **MISSING** | — | tool que recusa | — | — | — | dimensão vem da classe paramétrica (`*_class.derive_spec`), não de escala livre |
| `align_object` | **MISSING** | — | — | — | — | — | derivável de `move_object` + bbox; candidato barato |
| `place_against_wall` | **MISSING** | — | — | — | — | — | `spatial_model` tem as paredes; não exposto |

## Material e cor

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `set_color` | **READY** | `SceneStore.recolor` + tabela fechada em `colors.py` | `set_color` | ✅ `STATE_DELTA`: relê o `rgb`, igual ao anterior → UNVERIFIED | ✅ mesma fila do `translate` | ❌ cor não move nada; `gatesRun: false` explícito | **era o pedido do Felipe.** Visível no `.skp` via `apply_to_skp` |
| `set_material` | **MISSING** | `style_spec.texture_env` + `LAYOUT_TEX_MAP` no build | tool que recusa | — | — | — | TEXTURA é outra coisa que cor: precisa de PNG por kind. A recusa agora aponta `set_color` |
| `set_texture` | **MISSING** | `assets/textures/procedural` existe | tool que recusa | — | — | — | idem |

> **Ativo reaproveitável:** `tools/recolor_kitchen_theme.rb` faz exatamente o
> padrão certo — carrega um `.skp`, troca a pele dos materiais, salva noutro
> arquivo, **sem rebuildar geometria**. É temático e específico de cozinha
> (`ph_kc_*`), mas prova o caminho e o custo.

## Ciclo de vida de objeto

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `duplicate_object` | **MISSING** | — | — | — | — | — | — |
| `delete_object` | **MISSING** | — | tool que recusa (HIGH) | — | — | — | sem caminho reversível ainda |
| `replace_component` | **MISSING** | — | — | — | — | — | — |
| `create_object` | **MISSING** | cérebro de layout cria em lote | tool que recusa | — | — | — | viria de `furniture_class` |

## Histórico

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `undo` | **READY** | `SceneStore.undo` — inverso exato da translação | `undo` | ✅ contagem de edições | — | ❌ não revalida | rodar gates após desfazer |
| `redo` | **READY** | `SceneStore.redo` | `redo` | ✅ | — | ❌ | idem |
| `snapshot` | **READY** | cópia do documento inteiro | `save_snapshot` | ✅ arquivo existe | — | n/a | — |
| `restore_last_clean` | **PARTIAL** | `mark_clean` é manual (`clean=true`) | `restore_last_clean` | ✅ | — | ❌ | **nada marca CLEAN automaticamente após gate PASS** — por isso responde "nenhuma versão CLEAN marcada" |
| `list_history` | **READY** | log de edições | `list_history` | n/a | n/a | n/a | — |

## Validação

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `run_gates` | **READY** | `circulation_gate.gate` + `pairwise_overlap(_module_geom(...))` + `geometry_sanity.audit` sobre a cena EDITADA | `run_gates` | ✅ é a própria verificação | n/a | — | depende de `_module_geom` (API privada, sem teste — [H3](review/SLICE1_ARCHITECTURAL_REVIEW.md)) |
| `get_findings` | **READY** | roteado por `finding_router` real (FP-033) | `get_findings` | ✅ | n/a | — | rota `AGENT` não existe na taxonomia real |
| `run_correction_loop` | **MISSING** | `correction_loop.run_loop` | tool que recusa | — | — | — | **`run_loop` JÁ aceita `boxes=`** (`correction_loop.py:141`) → fiação, não redesenho |

## Visual

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `render` | **MISSING** | `render_scene_views.py`, `render_scene_vray.py` | tool que recusa | — | — | — | sobe SketchUp em lote |
| `render_room` | **MISSING** | `render_room.ps1` | tool que recusa | — | — | — | idem |
| `visual_review` | **MISSING** | `run_skp_visual_review.py` (69 KB), `oracle_providers` | tool que recusa | — | — | — | depende de render |
| `create_contact_sheet` | **MISSING** | `compose_side_by_side.py` | tool que recusa | — | — | — | idem |
| `set_camera` | **MISSING** | `auto_camera.py`, `screenshot_camera.rb` | tool que recusa | — | — | — | idem |

## Conhecimento (RAG)

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `search_knowledge` | **MISSING** | ⚠️ **não existe função com esse nome.** O real é `reference_db.retrieve(room, style, budget)` e `reference_db.query(con, ...)` | — | — | — | — | tool + Qdrant no ar (hoje DOWN) |
| `search_preferences` | **MISSING** | ⚠️ idem. `taste_writeback.py` grava; `project_memory_db.py` guarda | — | — | — | — | idem |

## Serviços

| capability | status | implementação | entry point | verified? | undoable? | gates? | falta |
|---|---|---|---|---|---|---|---|
| `status` | **READY** | `ServiceManager.status()` via `HttpHealthProbe` | aba Oráculo | ✅ HTTP real | n/a | n/a | — |
| `start` / `start_all` / `restart_failed` | **PARTIAL** | `ServiceManager` completo e testado (13 testes) | — | ✅ espera readiness | n/a | n/a | **sem botão na UI** — só o antigo de um serviço |
| `stop` | **MISSING** | — | — | — | — | — | deliberado: derrubar serviço é HIGH |

---

## Resumo

**A contagem saiu daqui em 2026-09-27.** Ela vive em
[`CAPABILITY_TABLE.md`](CAPABILITY_TABLE.md), gerada do registry. Manter número à
mão aqui foi exatamente o que produziu o drift — uma contagem escrita parece
medida e não é.

```bash
python tools/capability_table.py        # ver
python tools/capability_table.py --write  # regravar depois de mexer no registry
```

**Onde estava o valor represado** — os três MISSING que eram **fiação**, não
construção:

1. ~~`apply_to_skp`~~ — **feito no slice 2 (2026-09-20).** Era o elo que faltava:
   sem ele toda a operação morria no documento de cena.
2. ~~`set_color`~~ — **feito no slice 4 (2026-09-20).** Era o pedido original do
   Felipe ("troca a cor da cama para preto") e só fechou porque o slice 2 existe:
   sem materializar, a cor mudaria só na cena.
3. `run_correction_loop` → `run_loop()` já aceita `boxes=` — **o último**.

### A armadilha do slice 4, registrada para não ser re-descoberta

`place_layout_skp.rb:44` faz `m = model.materials[name]; return m if m` —
material é reusado **pelo NOME** e o `rgb` de quem chega depois é **ignorado**.
Trocar a cor mantendo o `ph_<kind>` compartilhado produziria um `.skp`
visualmente idêntico, em silêncio. Por isso `set_color` atribui um `mat_name`
próprio (`harness_<objectId>_<hex>`), determinístico e estável.
