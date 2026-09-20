# Fila de implementação — Codex

**Para:** Implementation Engineer · **De:** Lead Architect · 2026-09-19
**Base:** `feat/master-orchestrator` @ `e6d6b00`

Este arquivo é auto-contido. Não é preciso ler conversa nenhuma.
Contexto: [`HARNESS_MASTER_ORCHESTRATOR.md`](HARNESS_MASTER_ORCHESTRATOR.md) ·
[`CAPABILITY_MATRIX.md`](CAPABILITY_MATRIX.md) ·
[`review/SLICE1_ARCHITECTURAL_REVIEW.md`](review/SLICE1_ARCHITECTURAL_REVIEW.md)

## Regras de trabalho

- **Branch por slice**, de `feat/master-orchestrator`. Nunca direto em `develop`.
- `JAVA_HOME` **precisa** ser JDK 25 — com o 21 o surefire falha com "class file
  version 69.0".
- A suíte roda **sem** SketchUp, Ollama e Python: dublê para tudo que é externo.
- Schema de tool é publicado pelo **Python**; o Java consome. Não duplicar.
- **Não escrever no repo `sketchup-mcp`** — ele está com working tree suja de
  outro trabalho. É dependência de leitura.
- Capability nova só aparece **reabrindo o app** (tabela lida uma vez no boot).

---

## SLICE 1.5 — Separar CAPABILITY de EXECUTION + verificação `[PRIMEIRO]`

Destrava todos os outros. Sem isto, cada capability nova nasce sem verificação e
com o mesmo bug de recusa.

**Goal:** `NOT_IMPLEMENTED` vira resposta de *lookup*, antes de validar argumento
ou chamar handler. Toda tool declara como provar que executou.

**Arquivos**
- `capabilities/harness_caps/registry.py` — `ToolSpec` (+`implemented`,
  `output_schema`, `verification`), `Registry.invoke` (ordem das checagens)
- `src/main/java/harness/agent/domain/ToolSpec.java` — campos novos
- `src/main/java/harness/agent/domain/AgentRuntime.java` — estágio de verificação
- `src/main/java/harness/agent/domain/AgentOutcome.java` — status `UNVERIFIED`
- `src/main/java/harness/agent/source/StdioCapabilityHost.java` — ler campos novos

**Contratos**
1. `Registry.invoke` na ordem: **lookup → implemented? → schema → risco →
   handler → verificação**. Hoje o schema vem antes do `implemented` — é o
   [BLOCKER B1](review/SLICE1_ARCHITECTURAL_REVIEW.md).
2. `verification` ∈ `NONE | STATE_DELTA | PROCESS_HEALTH | ARTIFACT | GATE |
   EXTERNAL_JUDGE` (§5 da spec).
3. Execução ok + verificação falha → `UNVERIFIED`. **Nunca** `CLEAN`.
4. O trace grava a evidência: campo relido, esperado, encontrado.

**Testes de aceitação**
- `set_material({"object_id": "x", "color": "preto"})` → `NOT_IMPLEMENTED`,
  **não** `INVALID_ARGUMENTS` ← reproduz o bug do Felipe
- tool não implementada **não** chama handler e **não** gasta tentativa
- `move_object` com `STATE_DELTA` que não bate → `UNVERIFIED`
- `UNVERIFIED` não conta como `changedSystem()`
- trace contém `capability.lookup` e `tool.verified`

**Riscos:** mexe no caminho quente de toda tool. Rodar a suíte inteira (170+77) a
cada passo. `AgentRuntimeTest` tem 28 testes que cobrem o laço.

---

## SLICE 2 — `apply_to_skp`: a cena vira `.skp` ✅ **ENTREGUE 2026-09-20**

> Fechado. `Pipeline.materialize()` + tool `apply_to_skp` (verificação `ARTIFACT`),
> `verifyArtifact` no `AgentRuntime`, e `unmaterializedEdits` na cena.
> 15 testes Python novos + 2 Java (173 · 93, zero falhas).
>
> **O risco do `taskkill` foi decidido assim:** SketchUp aberto → **recusa**
> (`SKETCHUP_BUSY`, `needsHumanDecision=true`). Fechar exige `close_sketchup=true`.
> Matar a janela do Felipe por conta própria descartaria trabalho não salvo.
>
> **Não entregue de propósito:** o teste de integração que roda o SketchUp de
> verdade. Fica como item separado — precisa da máquina com o SketchUp instalado
> e não pode viver na suíte normal. Ver "o que falta" no fim desta seção.

**Goal:** fechar o critério 5 da fase 1. Hoje toda edição morre no documento de
cena e o `.skp` que o Felipe abre é de 2026-08-09.

**O trabalho é FIAÇÃO, não construção:** `tools/place_layout_skp.rb` já lê
`LAYOUT_BOXES` (JSON dos boxes) e `LAYOUT_OUT`/`LAYOUT_BEFORE`/`LAYOUT_AFTER_*`.
O padrão de invocação está em `tools/furnish_apartment.py:main()` — `taskkill`,
`Popen` com env, espera o log aparecer, `taskkill`.

**Arquivos**
- `capabilities/harness_caps/pipeline.py` — `materialize(boxes, out_path)`
- `capabilities/harness_caps/registry.py` — `apply_to_skp` deixa de recusar

**Contratos**
1. Entrada: os boxes **da cena editada** (`SceneStore.boxes()`), não do cérebro.
2. Saída: caminho novo. **Nunca sobrescrever o `.skp` canônico** sem snapshot.
3. `verification: ARTIFACT` — arquivo existe, mtime > início do comando,
   tamanho > 0. `.skb` de 0 byte é o sintoma clássico de falha.
4. `risk: MEDIUM`, `timeout` generoso (o SketchUp leva dezenas de segundos),
   `requires: ["pipeline", "SketchUp"]`.
5. Depois disso, a cena expõe `unmaterializedEdits` — quantas edições ainda não
   foram para o `.skp`.

**Testes**
- boxes da cena (com edições) chegam ao env `LAYOUT_BOXES`, não os do cérebro
- `.skp` de saída com 0 byte → `UNVERIFIED`, nunca sucesso
- timeout do SketchUp → falha declarada, sem travar o host
- caminho canônico nunca é o destino
- **um teste de integração marcado, fora do CI**, que roda o SketchUp de verdade

**Riscos:** único slice que depende do SketchUp instalado. `furnish_apartment` dá
`taskkill /F /IM SketchUp.exe` — **isso mata a janela que o Felipe tem aberta.**
~~Decidir e documentar: recusar se houver SketchUp aberto, ou avisar antes.~~
**Decidido: RECUSAR.** `SketchUpBusy` → erro tipado `SKETCHUP_BUSY` com
`needsHumanDecision=true`; fechar só com `close_sketchup=true`.

### O que falta deste slice

**Um teste de integração real, fora do CI.** Toda a fatia está coberta por
dublês (`FakeRunner`), que provam a fiação e a honestidade do resultado — mas
não provam que o `place_layout_skp.rb` aceita estes boxes e produz um `.skp`
abrível. Isso só fecha rodando o SketchUp de verdade, nesta máquina.

Como fazer quando houver a janela para isso: `apply_to_skp` numa cena com uma
edição conhecida → abrir o `.skp` de saída → conferir que o objeto moveu. É
verificação VISUAL, então o veredito é do Felipe.

---

## SLICE 3 — Service control na UI

**Goal:** `Start All` / `Restart Failed` / `Restart <serviço>` por clique.

Backend **pronto e testado** (`ServiceManager`, 13 testes). Falta só a UI —
menor slice da fila, destrava uso diário.

**Arquivos**
- `ui/src/OracleView.tsx` — botões
- `src/main/java/inspector/ui/InspectorApp.java` — `batchServices` já existe
- `ui/src/bridge.ts` — `requestServices(action)` já existe

**Contratos**
1. A UI manda **id/ação conhecidos**, nunca comando.
2. Nada dispara sozinho — lição do NOC; há teste travando.
3. Resultado por serviço, com o motivo real quando falha.

**Testes:** já cobertos no backend; acrescentar prova de que a UI não monta
comando.

**Riscos:** baixo. Não reintroduzir retry automático.

---

## SLICE 4 — Materiais e cor `[o pedido do Felipe]` ✅ **ENTREGUE 2026-09-20**

> `set_color` READY. Tabela fechada em `colors.py` (27 cores + sinônimos em
> inglês), `SceneStore.recolor` na mesma fila do `translate` (undo de graça),
> `verifyColorDelta` no Java. 17 testes Python + 2 Java.
>
> **A armadilha que quase deixou a fatia falsa:** `place_layout_skp.rb:44` reusa
> material **pelo nome** (`return m if m`) e ignora o `rgb` novo. Trocar a cor
> mantendo `ph_<kind>` daria um `.skp` idêntico, em silêncio. Resolvido com
> `mat_name` próprio por objeto+cor (`harness_<objectId>_<hex>`).
>
> **`set_material` continua MISSING de propósito** — textura é outra coisa que
> cor: precisa de PNG por kind via `LAYOUT_TEX_MAP`. A recusa agora aponta
> `set_color` em vez de só dizer "não implementado".

**Goal:** `"troca a cor da cama para preto"` funciona de ponta a ponta.

`tools/recolor_kitchen_theme.rb` prova o padrão: carrega `.skp`, troca a pele dos
materiais, salva noutro arquivo, **sem rebuildar geometria**. É temático e
específico de cozinha (`ph_kc_*`) — generalizar para "objeto X, cor Y".

**Arquivos**
- `capabilities/harness_caps/scene.py` — editar `rgb`/`mat_name` no box
- `capabilities/harness_caps/registry.py` — `set_color`, `set_material`
- `capabilities/harness_caps/pipeline.py` — nome de cor → RGB

**Contratos**
1. Edição de cor entra no **mesmo log de edições** do move: undo de graça.
2. Nome de cor → RGB é **tabela determinística**, não pergunta ao modelo.
3. `verification: STATE_DELTA` — reler o objeto, conferir o `rgb`.
4. Gates de geometria **não se aplicam**: cor não move nada. Não rodar e não
   fingir que rodou (§5.4 da spec).
5. `risk: MEDIUM`, `undoable: true`.

**Testes**
- `set_color` muda `rgb` de **todas** as peças do módulo
- undo restaura a cor exata
- cor desconhecida → erro tipado com as disponíveis, sem chutar
- `verification` pega um handler que diz ter mudado e não mudou

**Depende de:** slice 1.5 (verificação). Visível no `.skp` só com o slice 2.

---

## SLICE 5 — Agent loop com constraints

**Goal:** `"melhora a circulação desse quarto sem mexer na cama"`.

**Também é fiação:** `tools/correction_loop.py:135` — `run_loop()` **já aceita
`boxes=`**, `consensus=`, `room_poly=`, `detect=` injetável e `max_cycles`.
Assinatura hermética por design (sem rede, sem SketchUp).

**Arquivos**
- `capabilities/harness_caps/registry.py` — `run_correction_loop`
- `src/main/java/harness/agent/domain/AgentRuntime.java` — retry com constraints

**Contratos**
1. `lock_object` vira **constraint dura**: o loop não pode mover travado.
2. Determinístico primeiro. Só quando o `correction_loop` empaca o agente propõe
   re-layout.
3. Teto de tentativas; estourou → `NEEDS_FELIPE`. Nunca laço infinito.
4. `finding_router` continua decidindo a rota. **Não reimplementar como LLM.**
5. Rota `AGENT` não existe na taxonomia real (`DETERMINISTIC_AUTOFIX` /
   `NEEDS_VISION` / `NEEDS_FELIPE`). Decidir: estender o router no pipeline ou
   mapear no Harness. **Estender o pipeline é escrita em repo alheio — pedir
   antes.**

**Testes:** travado respeitado sob pressão · stall vira retry do agente · teto
respeitado · `NEEDS_FELIPE` com o que foi tentado.

**Riscos:** o maior da fila. Só entrar com verificação (1.5) pronta — sem ela o
loop pode "melhorar" sem provar nada.

---

## SLICE 6 — Visual

**Goal:** render → visual judge → finding estruturado → alteração.

**Arquivos (pipeline, leitura):** `render_scene_views.py`,
`render_scene_vray.py`, `run_skp_visual_review.py`, `oracle_providers.py`.

**Contratos**
1. `verification: ARTIFACT` para render; `EXTERNAL_JUDGE` para veredito.
2. **Veredito visual nunca é automático** — é do Felipe. O sistema prepara a
   evidência.
3. Requer GPT-Docker (hoje DOWN).

**Depende de:** slices 1.5 e 2.

---

## Dívidas transversais (encaixar onde couber)

| # | Dívida | Custo |
|---|---|---|
| D1 | teste de que `_module_geom`/`pairwise_overlap` existem no pipeline real ([H3](review/SLICE1_ARCHITECTURAL_REVIEW.md)) | baixo |
| D2 | gates nos DOIS cômodos quando o move cruza fronteira ([H2](review/SLICE1_ARCHITECTURAL_REVIEW.md)) | baixo |
| D3 | teste de contrato das chaves Python→Java ([M3](review/SLICE1_ARCHITECTURAL_REVIEW.md)) | baixo |
| D4 | marcar CLEAN automaticamente após gate PASS | baixo |
| D5 | `find_object` usar cômodo ativo para desempatar ([M2](review/SLICE1_ARCHITECTURAL_REVIEW.md)) | baixo |
| D6 | guarda anti-invenção conferir correspondência, não presença ([H1](review/SLICE1_ARCHITECTURAL_REVIEW.md)) | médio |
| D7 | RAG como tool (`reference_db.retrieve`/`query` — **não** `search_knowledge`) | médio |

## Ordem recomendada

```
1.5  →  2  →  3  →  4  →  5  →  6
        ↑        ↑
      D1 D2 D3 D4 D5      D6 D7
```

**1.5 primeiro, sem exceção.** É o único que muda contrato; quanto mais
capability existir antes dele, maior a migração.
