# Harness como Master Orchestrator — kickoff

**Data:** 2026-09-19 · **Branch:** `feat/master-orchestrator` · **Estado:** slice 1 entregue

Engenharia, não marketing. O que existia, o que falta, o que foi decidido e por quê.

---

## 1. Problema

Operar a planta exigia o Claude. Subir ambiente, mover um móvel, rodar gate,
desfazer — tudo passava por uma conversa. Três consequências:

1. **O conhecimento operacional ficava preso na conversa.** Cada sessão nova
   redescobria como rodar as coisas.
2. **Sem entrada direta.** Não havia um lugar onde escrever "move a escrivaninha
   30 cm para a esquerda" e isso acontecer.
3. **Sem memória de operação.** O pipeline recomputa a mobília a cada execução e
   entrega a lista de caixas ao Ruby por variável de ambiente. Nada fica
   guardado — então "move mais 10 cm" e "desfaz" não tinham onde existir.

## 2. Estado encontrado (inventário real, 2026-09-19)

### Harness App — `E:\Claude\apps\harness-app`
App **JavaFX** (Maven, JDK 25) hospedando uma UI React/Vite num `WebView`.
**Não é servidor**: não há HTTP, não há porta. 103 testes, 1 falhando.

Já existia e foi reaproveitado:

| Peça | O que já fazia |
|---|---|
| `TraceEvent` + `JsonlReplayTraceSource` + `Run` + `Pipeline` | envelope v1, replay, agrupamento por span, grafo |
| `TraceProjection` / `OracleProjection` | fronteira domínio→JSON |
| `ServiceTarget` / `ServiceHealth` / `HealthProbe` / `HttpHealthProbe` | sonda de saúde |
| `ServiceLauncher` / `ServiceAction` / `ProcessServiceLauncher` | **botão** que sobe serviço, com lista de comandos fechada em código |
| `WebBridge` + `bridge.ts` | ponte Java→JS sem `JSObject`; UI enfileira, Java puxa |
| `ComponentCatalog` / `ImplementationCatalog` / `SourceVerifier` | catálogo educacional conferido contra o repo Python |

### Pipeline — `E:\Claude\apps\sketchup-mcp`
| Peça | Situação |
|---|---|
| `tools/furnish_apartment.collect_boxes` | **cérebro determinístico**: 404 peças, 8 cômodos, ~40 s, sem SketchUp |
| `circulation_gate.gate(con, boxes, room)` | aceita boxes — usável sobre cena editada |
| `furniture_overlap_gate` | `overlap_gate(con, room)` **recomputa** os boxes; o núcleo puro é `pairwise_overlap(_module_geom(boxes))` |
| `geometry_sanity.audit(parts, rooms, to_m)` | núcleo puro, aceita boxes |
| `finding_router` (FP-033) | taxonomia real: `DETERMINISTIC_AUTOFIX` / `NEEDS_VISION` / `NEEDS_FELIPE` |
| `correction_loop` / `correction_fixes` | opera sobre consensus/SKP |
| `reference_db` (`retrieve`, `semantic_recall`) + `rag_embed_backend` | RAG por HTTP puro (urllib), Qdrant + Ollama |
| `oracle_providers` | provedores de visão (GPT-bridge, Ollama vision) |
| `tools/mcp_server/server.py` | MCP **fatia 1**: só tools puras |

### Achados que mudaram o desenho

1. **Não existe "SketchUp MCP" vivo.** Não há socket nem serviço. O SketchUp é
   invocado **em lote**: `SketchUp.exe <arquivo.skp> -RubyStartup <script.rb>`,
   com os boxes passados por variável de ambiente. O diagrama da missão supunha
   um serviço; a realidade é batch. A operação inteligente acontece sobre o
   **documento de cena**, e materializar no `.skp` é um passo separado.
2. **`core/observability/` não está na branch que está na pasta.** Vive em
   `feat/ai-pipeline-inspector-observability`. É a causa da única falha de teste
   pré-existente.
3. **Não havia estado persistido de projeto.** Era o furo que impedia comando
   contextual e undo. Virou a peça central do slice 1.
4. **O `circulation_gate` não usa `fails`/`warns`** — devolve `checks` aninhado.
   Sem achatar, um FAIL chegava na tela sem motivo.

## 3. Arquitetura alvo

```
Felipe → UI (React) → ControlPlane → AgentRuntime → LlmPlanner (Qwen/Ollama)
                           │              ↓
                           │        ToolRegistry  ← risco, schema, reversibilidade
                           │              ↓
                           │     CapabilityHost (processo filho Python, NDJSON)
                           │              ↓
                           │   SceneStore · gates · finding_router · sketchup-mcp
                           │
                           └→ ServiceManager (status/start/restartFailed, por clique)
```

**Decisões e os porquês:**

| Decisão | Por quê |
|---|---|
| Capability plane em **Python**, não Java | os gates, o cérebro de layout e o RAG são Python. Reescrever em Java criaria dois lugares para a mesma regra divergirem. |
| **Processo filho NDJSON**, não HTTP | um servidor é mais uma coisa viva que ninguém derruba. Filho morre com o pai — a mesma regra do Inspector. |
| Schemas publicados **pelo Python** | fonte única. Duplicar no Java criaria dois contratos divergindo em silêncio, como já aconteceu com o envelope entre repos. |
| Agente grava no **envelope v1 existente** | um contrato de trace no sistema, não dois. Comando aparece na Pipeline View de graça. |
| Cena = **baseline + log de edições** | reprodutível (dá para reconstruir do zero), undo exato (translação é invertível) e auditável. |
| Código novo em `harness.*`, não `inspector.*` | a nomenclatura passa a dizer a arquitetura: observabilidade é um plano, controle é outro. Renomear `inspector.*` seria churn sem ganho. |

## 4. Escopo

**Dentro (slice 1):** estado de projeto persistido · tool registry tipado ·
agent runtime governado · planner local · gates automáticos sobre a edição ·
undo/redo/snapshot · service manager · trace · barra de comando · CLI.

**Fora, por enquanto (declarado como `unsupported`, com motivo):** rotacionar,
escalar, criar/apagar objeto, material/textura, render, visual judge, contact
sheet, câmera, `correction_loop` sobre a cena editada, materializar no `.skp`.

## 5. Riscos

| Risco | Mitigação |
|---|---|
| Modelo local inventa id ou argumento | registry recusa e devolve o erro; `find_object` obrigatório; ambiguidade **para** e pergunta |
| Modelo não usa o canal de tool calling | já aconteceu: `qwen2.5-coder` escreve a chamada em `content`. Parser reconhece a forma; o nome ainda passa pelo registry |
| Gate parecer aprovado quando não rodou | `UNAVAILABLE` é resultado próprio e não conta como CLEAN |
| Ressuscitar o NOC sem querer | `startAll`/`restartFailed` só por ação; teste trava a ausência de watchdog |
| Divergência de contrato entre repos | `AgentTraceContractTest` + schemas publicados por um lado só |
| Cena divergir do `.skp` real | a cena declara sua proveniência (`consensus`, `ptToM`, builder). Materializar é passo explícito, ainda não implementado |
| `PT_TO_M` errado | congelado em `harness.json` e aplicado antes de `core.scale` ser importado |

## 6. Migração incremental

| Slice | O que prova | Estado |
|---|---|---|
| **1 — Master Control Minimum** | comando → modelo → tool → sistema → gate → trace → undo | **ENTREGUE** |
| 2 — Service control | status/start/stopAll/restartFailed na tela | backend pronto; falta o botão |
| 3 — Comandos contextuais | "move mais 10 cm", "agora gira", "a outra mesa" | estado pronto; falta exercitar com o modelo |
| 4 — Agent loop | "melhora a circulação sem mexer na cama" — constraints + correction loop + retry + `NEEDS_FELIPE` | não começado |
| 5 — Visual | render → visual judge → finding estruturado → alteração | não começado |

## 7. Critérios de aceitação da fase 1

| # | Critério | Como se comprova |
|---|---|---|
| 1 | Harness mostra saúde real | `HarnessCli` lista ollama/qdrant/gpt com o motivo real quando fora |
| 2 | Comando entra pelo Harness | barra de comando + `sendCommand` + `HarnessCli` |
| 3 | Chega ao modelo local | `agent.plan` no trace, com o model id |
| 4 | Modelo gera chamada validada | `tool.invoke` no trace; argumento inválido vira `INVALID_ARGUMENTS` |
| 5 | Alteração real no sistema | 6 peças da escrivaninha transladadas na cena persistida |
| 6 | Gates rodam automaticamente | `gate.run` no trace sem o modelo pedir |
| 7 | Correction loop preservado | `finding_router` real classifica os findings; nada foi reimplementado |
| 8 | Inspector mostra o que houve | `AgentTraceContractTest` projeta o trace do agente |
| 9 | Dá para desfazer | `"desfaz"` → 0 edições pendentes |
| 10 | Documentação | este arquivo + `CLAUDE.md` + `HANDOFF.md` + `ITERATIONS.md` |
| 11 | Cobertura | 165 testes Java + 68 Python, sem SketchUp/Ollama/Python no CI |

## 8. Definition of done — fase 1

Fechada quando os 11 critérios acima forem demonstráveis num terminal limpo.
**Estão.** A prova reproduzível está em `HANDOFF.md`.

O que **não** está fechado, e é honesto dizer: a cena editada ainda não vira
`.skp`; render e visual judge não existem no registry; o correction loop ainda
não opera sobre a cena editada; e o agent loop (slice 4) não começou.
