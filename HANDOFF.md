# HANDOFF — harness-app

> Onde estamos, agora. Quem pegar isto não deve precisar do histórico da conversa.

**Atualizado:** 2026-09-19 · **Branch:** `feat/master-orchestrator` (de `develop`)

---

## Onde estamos

O Harness deixou de ser só observabilidade do pipeline e virou o **Control Plane**
da operação da planta. O slice 1 está entregue e **demonstrável**: um comando em
português entra pelo Harness, o modelo local escolhe a capability, o Harness
executa sobre a cena real da `planta_74`, os gates rodam sozinhos, o trace é
gravado no envelope que o Inspector lê, e dá para desfazer.

A decisão arquitetural canônica e as regras que não devem ser revertidas estão em
[`CLAUDE.md`](CLAUDE.md). O desenho e o escopo estão em
[`docs/HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md`](docs/HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md).

## O que foi implementado

**Capability plane** (`capabilities/harness_caps/`, Python, roda no venv do pipeline)
- `config.py` — configuração central, mesmas chaves do lado Java.
- `pipeline.py` — único ponto que importa `sketchup-mcp`; import guardado,
  indisponibilidade **declarada** em vez de stub.
- `scene.py` — `SceneStore`: baseline determinística + log de edições invertíveis,
  ids estáveis (`suite_01.escrivaninha`), busca em linguagem natural, travas,
  snapshots, undo/redo.
- `gates.py` — circulation/overlap/geometry sobre a cena **editada**, findings
  roteados pelo `finding_router` real (FP-033).
- `registry.py` — 20 capabilities tipadas + 12 declaradas `unsupported` com motivo.
- `host.py` — NDJSON stdin/stdout, processo filho.

**Control plane** (`src/main/java/harness/`)
- `agent/domain/` — `ToolSpec`, `ToolCall`, `ToolResult`, `Risk`, `ToolRegistry`,
  `AgentState`, `AgentRuntime`, `AgentOutcome`, `AgentTrace`, ports
  `CapabilityHost` / `LlmPlanner` / `TraceRecorder`.
- `agent/source/` — `StdioCapabilityHost`, `OllamaPlanner`, `JsonlTraceRecorder`.
- `service/` — `ServiceManager` (status/start/startAll/restartFailed) + `ServiceRegistry`.
- `config/HarnessConfig`, `projection/AgentProjection`, `ui/ControlPlane`,
  `cli/HarnessCli`.

**UI** — aba **Agente** com barra de comando, log, lista de ações reais, painel de
gates com o motivo, e a tabela de capabilities (incluindo o que ainda não existe).

## Contratos adicionados

| Contrato | Onde | Quem manda |
|---|---|---|
| Tool registry (nome, schema, risco, reversível, requisitos, timeout) | `registry.py` → `describe` | **Python** publica, Java consome |
| Protocolo do host | NDJSON: `{id, method: ping\|describe\|invoke\|shutdown}` | ambos |
| Documento de cena | `state-local/<projeto>.scene.json`, `schemaVersion: 2` | `scene.py` |
| Trace do agente | envelope v1 (`TraceEvent`) — `run.started`, `agent.plan`, `tool.invoke`, `tool.rejected`, `gate.run`, `run.finished` | compartilhado com o Inspector |
| Configuração | `harness.json` | lido pelos dois lados |

## Testes

| Suíte | Resultado |
|---|---|
| Java (`./mvnw test`) | **165**, 1 falha **pré-existente** |
| Python (`cd capabilities && python -m pytest`) | **68 passed** |

Baseline antes desta iniciativa: 103 Java, mesma 1 falha.

**A falha pré-existente não é regressão daqui.** `ImplementationCatalogTest`
confere o catálogo contra o repo do pipeline, e `core/observability/context.py` +
`_faceted_rank` estão na branch `feat/ai-pipeline-inspector-observability`, não na
`feat/apartamento-mobiliado-completo` que está na pasta. Conserto real: fazer
checkout da branch certa ou landar a observabilidade na develop do pipeline.

Nada na suíte precisa de SketchUp, Ollama ou Python — tudo por dublê.

## Serviços necessários

| Para | Precisa |
|---|---|
| rodar a suíte | nada |
| operar de verdade (comando) | **Ollama** com o modelo de `harness.json` + venv do pipeline |
| RAG | Qdrant (hoje **DOWN**) |
| veredito visual | GPT-Docker (hoje **DOWN**) |

Sem Ollama o Harness continua funcionando para o caminho determinístico e **diz**
que não interpreta comando. Não é all-or-nothing.

## Como executar

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.2.10-hotspot"

./mvnw test                                   # suíte Java
cd capabilities && python -m pytest           # suíte Python (venv do pipeline)
./mvnw javafx:run                             # o app

# control plane sem janela — prova reproduzível
./mvnw -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes;$(cat target/cp.txt)" harness.cli.HarnessCli "move a escrivaninha 30 cm para a esquerda"
```

⚠️ `JAVA_HOME` **precisa** ser JDK 25. Com o 21, surefire falha com
"class file version 69.0".

## Como reproduzir a demonstração

Executado de verdade em 2026-09-19 (saída resumida):

```
== comando ==  move a escrivaninha 30 cm para a esquerda

== desfecho: GATE_FAILED (6,8s) ==
  A escrivaninha foi movida 30 cm para a esquerda, mas isso bloqueou a
  circulação no corredor principal.
  trace: cmd_20260919T004319_802715700Z  ·  desfazível: true

== o que rodou de verdade ==
  OK   find_object      1 objeto(s) encontrado(s)
  OK   move_object      Escrivaninha movida 300.0 mm para esquerda (6 peças)

== gates ==
  r000 -> FAIL     circulation: FAIL · overlap: WARN · geometry: PASS
```

```
== comando ==  desfaz
== desfecho: CLEAN (1,4s) ==   alteração desfeita
  OK   undo             alteração desfeita
```

Trace gravado (11 eventos, com parentesco de span):

```
 1 s001        AGENT started  run.started   harness.agent
 2 s002        LLM   started  agent.plan    ollama.qwen2.5-coder:14b
 3 s002        LLM   ok       agent.plan
 4 s003 <-s002 TOOL  ok       tool.invoke   capability.find_object
 7 s005 <-s004 TOOL  ok       tool.invoke   capability.move_object
 8 s006 <-s004 GATE  error    gate.run      gate.deterministic
11 s008        AGENT error    run.finished  harness.agent
```

**Leia o GATE_FAILED com cuidado:** a `SUITE 01` **já reprovava circulação antes
do comando** — um portal PRIMARY que tem 1,00 m vazio vai a 0,00 m com a mobília
da baseline. O move piorou (colisão escrivaninha × guarda-roupa, 24 %), mas o
FAIL não nasceu dele. Isso é um achado sobre a planta, não sobre o agente.

## O que ainda NÃO funciona

1. **A cena editada não vira `.skp`.** Materializar exige rodar o SketchUp em
   lote. Declarado como `apply_to_skp` em `unsupported`.
2. **Sem render, visual judge, contact sheet, câmera** no registry (slice 5).
3. **Sem rotacionar / escalar / criar / apagar / material.** Rotação exige
   regenerar pelo builder, não transladar — não dá para fingir com translação.
4. **`correction_loop` não opera sobre a cena editada.** Ele age sobre
   consensus/SKP. O roteamento de findings já usa o router real; o loop em si não
   foi ligado.
5. **RAG não está no contexto do agente.** `search_knowledge` / `search_preferences`
   existem no pipeline e ainda não viraram tool.
6. **Agent loop (slice 4) não começou.** Hoje o runtime faz várias tentativas, mas
   não há re-layout com constraints.
7. **Botões de serviço na UI.** `ServiceManager` está pronto e testado; a UI ainda
   só tem o botão antigo de ligar um serviço.
8. **O modelo leva ~6 s por comando simples** (`qwen2.5-coder:14b`). Aceitável,
   mas vale medir alternativas antes do agent loop, onde são várias rodadas.

## Blockers

Nenhum bloqueia a próxima fatia. Dois pontos de atenção:

- **Qdrant e GPT-Docker estão fora.** Não impedem o slice 1; impedem RAG e
  veredito visual.
- **`sketchup-mcp` está com working tree sujo** (`bedroom_designer.py`,
  `furnish_apartment.py` modificados; `service_layout.py`, `.github/agents/`,
  `inspector-desktop/` untracked) e em `feat/apartamento-mobiliado-completo`.
  **Nada foi tocado lá** — esta iniciativa não escreveu uma linha no pipeline.
  Mas `furnish_apartment` importa `tools.service_layout`, que é **untracked**:
  num checkout limpo o `collect_boxes` não importa. Vale landar aquilo.

## Decisões que NÃO devem ser revertidas

1. Harness é o Master Orchestrator; Claude não é dependência de runtime.
2. O modelo não toca o sistema — só tool declarada, com argumento validado.
3. O veredito é do gate, não do texto do modelo. Alteração geométrica dispara
   gate automaticamente.
4. Nada ressuscita sozinho. Botão sim, watchdog nunca.
5. Indisponível é resposta: `UNAVAILABLE` ≠ PASS; `unsupported` é publicado com
   motivo.
6. Um envelope de trace só, compartilhado com o Inspector.
7. Schemas publicados por um lado só (Python).
8. Risco HIGH exige confirmação humana.

## Próxima fatia

**Slice 2 — Service control na tela** (menor e destrava uso diário):
botões `Start All` / `Restart Failed` / `Restart <serviço>`, usando
`requestServices` que já está na ponte e o `ServiceManager` que já está testado.

Depois, em ordem: **slice 3** (comandos contextuais exercitados com o modelo
real), **slice 4** (agent loop com constraints + correction loop), **slice 5**
(visual).

Um item transversal que paga rápido: expor `search_knowledge` /
`search_preferences` como tools, para o agente consultar preferência aprovada
antes de propor layout.
