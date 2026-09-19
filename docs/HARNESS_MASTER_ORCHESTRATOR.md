# Harness Master Orchestrator — spec canônica

**Versão:** 1.0 · 2026-09-19 · **Estado:** alvo definido, slice 1 parcial

Este é o documento de contrato. Onde ele e o código divergirem, é **bug do
código** — exceto onde marcado `AINDA NÃO`, que é escopo declarado.

Companheiros: [`CAPABILITY_MATRIX.md`](CAPABILITY_MATRIX.md) (o que existe) ·
[`review/SLICE1_ARCHITECTURAL_REVIEW.md`](review/SLICE1_ARCHITECTURAL_REVIEW.md)
(onde diverge hoje) · [`CODEX_QUEUE.md`](CODEX_QUEUE.md) (o que implementar) ·
[`HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md`](HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md)
(inventário original e histórico da decisão).

---

## 1. Decisão arquitetural canônica

```
HARNESS APP  ← autoridade operacional
   ├── Agent Runtime ──── Qwen / modelo local   (um componente, não o dono)
   ├── Tool Registry
   ├── Service Manager
   ├── State / Context
   ├── Trace / Inspector
   ├── SketchUp (execução em LOTE, não serviço vivo)
   ├── Gates
   ├── Correction Loop
   ├── RAG / Qdrant
   └── Renderer / Visual Judge
```

O Harness é o único ponto de entrada operacional recomendado. **Claude/Codex não
são dependência de runtime** — desenvolvem o Harness, não operam por ele.

### Correção factual ao diagrama original

O briefing desenha `SketchUp MCP` como serviço na árvore. **Não existe serviço
vivo de SketchUp.** O SketchUp é invocado em lote:

```
SketchUp.exe <arquivo.skp> -RubyStartup <script.rb>     (boxes via env var)
```

E `tools/mcp_server/server.py` é a fatia 1 do MCP: expõe só verbos PUROS (gates,
classes paramétricas, inventário) e declara no próprio docstring que as tools
pesadas "ficam pra fatia 2". Qualquer desenho que suponha `move_object` chegando
ao SketchUp por MCP vivo está errado sobre esta máquina.

**Consequência de arquitetura:** a operação inteligente acontece sobre o
**documento de cena**, e materializar no `.skp` é um passo explícito, em lote,
com custo (dezenas de segundos) — não um efeito colateral de cada comando.

---

## 2. Os quatro estágios

A confusão que produziu `troca a cor da cama → apply_to_skp NOT_IMPLEMENTED` vem
de existirem hoje só dois estágios. O alvo tem quatro, e eles são **camadas
distintas**, com respostas distintas:

```
UNDERSTANDING  → intenção estruturada          (modelo local)
CAPABILITY     → esta intenção é executável?   (registry — ANTES de executar)
EXECUTION      → faz                           (handler)
VERIFICATION   → prova que fez                 (estratégia declarada na tool)
```

**Regra:** `NOT_IMPLEMENTED` é resposta de **CAPABILITY**, nunca de EXECUTION.
Se o usuário pede algo que o sistema não faz, ele descobre no lookup — sem
chamar handler, sem gastar tentativa, sem mensagem sobre argumento.

**Regra:** `AGENT CLAIMS ARE NEVER EXECUTION EVIDENCE.` "O modelo disse que fez"
e "o handler não lançou" são a mesma classe de não-evidência.

---

## 3. Intent → capability

### 3.1 Intenção estruturada

O modelo produz intenção, não comando:

```json
{
  "intent": "CHANGE_MATERIAL",
  "target":     { "reference": "cama" },
  "parameters": { "color": "preto" },
  "grounding":  { "measurements_stated": [], "room_stated": null }
}
```

`grounding` é o contrato anti-invenção: declara o que o **usuário** disse. O
Harness compara com os parâmetros preenchidos. Hoje isso é inferido do texto do
comando (`AgentRuntime.fabricatedMeasurement`) e por isso só detecta lacuna
total, não distorção — ver [H1](review/SLICE1_ARCHITECTURAL_REVIEW.md).

### 3.2 Resolução de alvo

```
resolve_target("cama")
  → normaliza (sem acento, sem artigo: "a cama" → "cama")
  → casa exato → substring → token, parando no primeiro nível que achar
  → 0 resultados  → NOT_FOUND, com o que existe perto
  → 1 resultado   → object_id
  → N resultados  → desempata por: cômodo ativo → último referenciado
                  → ainda N: AMBIGUOUS com candidatos. NUNCA escolhe.
```

**Nunca inventar id.** Nem de objeto, nem de cômodo. `room_id` só de
`list_rooms` ou de resultado anterior.

### 3.3 Fluxo completo

```
comando
  → UNDERSTANDING  intenção + grounding
  → target          resolve_target → object_id (ou pergunta)
  → contexto        estado explícito (§6), não memória textual
  → CAPABILITY      registry: existe? risco? argumento válido? medida fundamentada?
                    ↳ não existe → NOT_IMPLEMENTED com o motivo  [PARA AQUI]
                    ↳ risco HIGH → NEEDS_CONFIRMATION            [PARA AQUI]
                    ↳ medida inventada → proposta p/ confirmar   [PARA AQUI]
  → EXECUTION       handler
  → VERIFICATION    estratégia declarada (§5)
  → GATES           se tocou geometria — automático
  → TRACE           evento estruturado por estágio
  → STATE           aprende do RESULTADO, nunca da narrativa
```

---

## 4. Tool Registry — contrato canônico

Cada tool declara:

| campo | obrigatório | significado |
|---|---|---|
| `name` | ✅ | identificador estável |
| `description` | ✅ | o que o modelo lê para escolher |
| `input_schema` | ✅ | JSON Schema; argumento fora dele é RECUSADO |
| `output_schema` | ✅ **AINDA NÃO** | forma do retorno; é o que o `AgentState` lê |
| `handler` | ✅ | quem executa |
| `risk` | ✅ | LOW / MEDIUM / HIGH — HIGH exige confirmação humana |
| `undoable` | ✅ | se há caminho de volta |
| `requires` | ✅ | dependências (`pipeline`, `scene`, `SketchUp`…) |
| `timeout` | ✅ | teto por chamada |
| `verification` | ✅ **AINDA NÃO** | como provar que ocorreu (§5) |
| `mutates` | ✅ | se ALTERA o projeto. Leitura bem-sucedida não é "mudou" |
| `implemented` | ✅ **AINDA NÃO** | separa CAPABILITY de EXECUTION (§2) |

**Fonte única:** o schema é publicado pelo **Python** (`registry.py` →
`describe`); o Java consome. Duplicar cria dois contratos que divergem em
silêncio.

**Capability não implementada é tool REGISTRADA que recusa** — não é ausência.
Modelo escolhe tool por nome e ignora proibição em prosa; testado e confirmado.
Mas a recusa tem de vir do lookup, antes da validação de argumento
([B1](review/SLICE1_ARCHITECTURAL_REVIEW.md)).

---

## 5. Modelo de verificação

> Uma operação não está concluída porque o handler retornou.

Cada tool declara **como** provar. Categorias:

| categoria | tools | evidência exigida |
|---|---|---|
| `NONE` | leituras puras | nenhuma — não alteram nada |
| `STATE_DELTA` | `move_object`, `set_color` | reler o alvo e comparar o campo alterado com o esperado |
| `PROCESS_HEALTH` | `open_sketchup` | processo vivo **e** respondendo, após janela de readiness |
| `ARTIFACT` | `apply_to_skp`, `render` | arquivo existe, mtime posterior ao comando, tamanho > 0 |
| `GATE` | `run_gates` | é a própria verificação |
| `EXTERNAL_JUDGE` | `visual_review` | veredito estruturado de quem tem autoridade visual |

Regras:

1. **Verificação é do Harness, não do handler.** O handler faz; o runtime
   confere. Um handler que se autodeclara verificado não é evidência.
2. **Falha de verificação com execução bem-sucedida = `UNVERIFIED`**, status
   próprio. Não é sucesso nem falha: é "fez e não consegui provar".
3. **O trace grava a evidência**, não só o resultado — qual campo foi relido,
   qual valor esperado, qual encontrado.
4. **`UNAVAILABLE` ≠ `PASS`.** Gate que não rodou nunca aprova.

### Divergência cena × `.skp`

Enquanto `apply_to_skp` não existir, o sistema **precisa expor** que a cena tem
N edições não materializadas e que o `.skp` no disco é de outra data. Ausência de
aviso é afirmação implícita de que estão sincronizados.

---

## 6. Modelo de contexto

Estado **explícito**, nunca memória textual do modelo — o modelo esquece,
alucina e reordena.

| campo | fonte | existe? |
|---|---|---|
| `active_project` | config / `open_project` | ✅ |
| `active_room` | resultado de tool | ✅ |
| `active_selection` | — | ❌ |
| `last_referenced_object` | resultado de tool | ✅ |
| `last_action` | resultado de tool | ✅ |
| `last_clean_snapshot` | `save_snapshot(clean=true)` | ⚠️ só manual |
| `locked_objects` | `lock_object` | ✅ |
| `pending_findings` | `run_gates` | ❌ não persiste entre comandos |
| `current_iteration` | — | ❌ |
| `current_model` | config | ✅ |

**Regra de ouro:** o estado aprende do **resultado da tool**, nunca do texto do
modelo. Se ele disser que moveu e a tool responder ambíguo, nada muda.

Sustenta: `"move mais 10 cm"` (último objeto) · `"agora gira"` (último objeto +
última ação) · `"desfaz"` (log de edições) · `"na outra mesa"` (candidatos do
último `find_object`, **ainda não**).

---

## 7. Service Manager

Lifecycle é **determinístico**. LLM não decide subir container.

```
status(id) · status() · start(id) · stop(id) · restart(id) · start_all() · restart_failed()
```

| serviço | sonda | ação |
|---|---|---|
| Ollama | `/api/tags` **e o modelo configurado presente** | `ollama serve` |
| Qdrant | `/collections` | `docker compose -f docker-compose.rag.yml up -d` |
| GPT-Docker | `/health` | `docker compose up -d` |
| Docker Desktop | — | `start "" "Docker Desktop.exe"` |
| Capability host | processo filho + tabela de tools lida | ciclo de vida do app |
| SketchUp | processo | lote, sob demanda |
| Renderer | — | lote, sob demanda |

Regras inegociáveis:

1. **Nada ressuscita sozinho.** `start_all` e `restart_failed` são AÇÃO de
   gente. Sem watchdog, sem retry em laço, sem reagir a "caiu" — lição do NOC,
   com teste travando.
2. **Comandos são lista FECHADA em código.** A UI manda um id conhecido; nunca
   uma linha de comando.
3. **Sem `powershell -ExecutionPolicy Bypass`** — foi o padrão que o Defender
   flagou (ThreatID 2147941383).
4. **Sequencial, não paralelo** — Qdrant e GPT dependem do Docker; paralelo
   esconde a causa raiz.
5. **Exit 0 não é readiness.** Espera a sonda responder; senão reporta que o
   comando rodou e o serviço não subiu.
6. **Subir o que já responde é proibido** — gera container duplicado.

---

## 8. Trace

Envelope **v1**, o mesmo que o Inspector lê (`inspector.domain.TraceEvent`). Um
contrato de trace no sistema, não dois — por isso um comando aparece na Pipeline
View sem código de visualização novo.

```
run.started → agent.plan → tool.invoke ─┬→ gate.run → run.finished
                          tool.rejected ┘
```

Span do plano é **pai** das chamadas que ele gerou. Sem parentesco o grafo vira
fila plana.

`AINDA NÃO`: `capability.lookup` (estágio 2) e `tool.verified` (estágio 4) —
hoje esses dois estágios não deixam rastro.

---

## 9. Segurança

1. **O modelo não toca o sistema.** Tool declarada, argumento tipado. Sem shell.
2. **Risco HIGH exige confirmação humana.** `autoApproveHighRisk=false`.
3. **Medida que o usuário não disse não vira alteração.**
4. **Gate verde não valida alteração inventada** — "é válido?" ≠ "foi isto que
   pediram?".
5. **Destrutivo precisa de caminho de volta** antes de existir.
6. **Nunca sobrescrever o `.skp` canônico sem snapshot.**
7. **Degradação parcial, nunca all-or-nothing.**

---

## 10. Vertical slices

| # | Objetivo | Estado |
|---|---|---|
| 1 | comando → modelo → tool → cena → gate → trace → undo | **PARCIAL** — opera a cena, não o `.skp` |
| 1.5 | **separar CAPABILITY de EXECUTION + verificação** | **próximo** — destrava o resto |
| 2 | `apply_to_skp` — a cena vira `.skp` | fiação (`place_layout_skp.rb` já lê `LAYOUT_BOXES`) |
| 3 | service control na UI | backend pronto e testado |
| 4 | materiais e cor | `recolor_kitchen_theme.rb` prova o padrão |
| 5 | agent loop com constraints + correction loop | `run_loop()` já aceita `boxes=` |
| 6 | visual: render → judge → finding → alteração | não começado |

## 11. Critérios de aceitação da fase 1

| # | Critério | Hoje |
|---|---|---|
| 1 | saúde real dos componentes | ✅ |
| 2 | comando entra pelo Harness | ✅ |
| 3 | chega ao modelo local | ✅ |
| 4 | chamada estruturada validada | ✅ |
| 5 | **alteração real no sistema** | ⚠️ cena, não `.skp` |
| 6 | gates automáticos | ✅ |
| 7 | correction loop preservado | ✅ roteamento; loop não ligado |
| 8 | Inspector mostra o ocorrido | ✅ |
| 9 | undo | ✅ |
| 10 | documentação | ✅ |
| 11 | cobertura sem SketchUp/Ollama/Python | ✅ 170 + 77 |
| 12 | **execução verificada** | ❌ |
| 13 | **capability lookup separado de execução** | ❌ |

A fase 1 fecha quando 5, 12 e 13 forem verdadeiros.
