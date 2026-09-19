# Claude Operating Instructions — `harness-app`

> Bootloader deste repo. Curto por design. A narrativa longa vive em
> `README.md`; o estado de agora vive em `HANDOFF.md`.

## Missão (decisão arquitetural canônica)

**O Harness App é o Master Orchestrator / Control Plane do sistema.**

Ele deixou de ser só observabilidade do pipeline. É o **produto principal para
operar a planta**.

> Se o sistema é capaz de fazer uma alteração, inspeção, validação, renderização,
> correção ou operação relacionada à planta, essa capacidade deve poder ser
> iniciada, acompanhada, validada, auditada e — quando aplicável — desfeita a
> partir do Harness App.

**Regra de evolução:** toda capability operacional nova entra no Tool Registry e
passa a ser usável pelo modelo local. Conhecimento operacional não fica preso na
conversa do Claude.

Não reverta a separação abaixo sem uma razão técnica **escrita**.

## Quem decide o quê

| Autoridade | Responde por |
|---|---|
| **Código determinístico** | geometria, bounding box, overlap, clearance, circulação, limites do cômodo, health, lifecycle de serviço, snapshot/undo. **LLM não substitui cálculo.** |
| **Qwen local** (Ollama) | interpretar linguagem natural, resolver referência de contexto, escolher tool, preencher argumento, ler finding, resumir. **Não é fonte de verdade de geometria e não declara nada válido.** |
| **Gates + correction loop** | "a alteração é válida?" — autoridade sobre regra objetiva. |
| **Visual judge / VLM** | só o que depende de imagem. |
| **Felipe** | decisão subjetiva e aprovação final. |
| **Claude / Codex** | ferramenta de DESENVOLVIMENTO. **Não é dependência de runtime.** |

O fluxo normal de operação **não passa por Claude**.

## Os dois planos

```
harness.*    CONTROL PLANE   — opera a planta (agente, tools, serviços, estado)
inspector.*  OBSERVABILIDADE — lê trace, projeta, desenha (Pipeline View)
```

Os dois se falam por **um contrato só**: o envelope v1 (`inspector.domain.TraceEvent`).
O agente grava no mesmo formato que o Inspector lê — por isso um comando aparece
na Pipeline View sem código de visualização novo. `AgentTraceContractTest` trava
essa costura.

## Arquitetura

```
Felipe → UI (React) → ControlPlane → AgentRuntime → LlmPlanner (Qwen/Ollama)
                                          ↓
                                    ToolRegistry  ← governança: risco, schema
                                          ↓
                              CapabilityHost (processo filho Python, NDJSON)
                                          ↓
                          SceneStore · gates · finding_router · sketchup-mcp
```

- **Capability plane** em `capabilities/harness_caps/` (Python). Roda com o venv
  do pipeline porque é lá que moram shapely e os gates.
- **Control plane** em `src/main/java/harness/`.
- O host é **processo FILHO** e morre com a janela. Sem porta, sem daemon.

## Hard rules

1. **O modelo não toca o sistema.** Ele escolhe uma tool declarada e preenche
   argumentos tipados; quem executa é o Harness. Não existe shell para o modelo.
   Tool fora do registry não vira comando — nunca.
2. **Sucesso ≠ "o Qwen respondeu".** Só é sucesso quando: intenção entendida +
   tool executada + estado mudou + validação rodou + resultado persistido +
   trace gravado. Alteração geométrica **sempre** dispara gate, automaticamente.
3. **Gate verde NÃO valida alteração inventada.** O gate responde "isto é
   válido?", não "foi isto que pediram?". Medida que o usuário não disse não vira
   alteração: `AgentRuntime.fabricatedMeasurement` barra a chamada e devolve a
   proposta para confirmação. Guarda determinística — prompt é pedido, não
   garantia. Veio de `"altere a cama dos quartos"` virar um move de 10 cm que os
   gates aprovaram.
4. **Capability que não existe RECUSA com o motivo.** As 12 pendentes são TOOLS
   registradas cujo handler levanta `NOT_IMPLEMENTED`. Não escondê-las é
   deliberado: modelo escolhe tool por nome e ignora proibição em prosa — tentei
   por prompt e ele recaiu na mesma sessão. Erro é dado, como no resto do sistema.
5. **Nada ressuscita sozinho.** `startAll`/`restartFailed` existem como AÇÃO de
   gente, nunca como reflexo. Sem watchdog, sem retry em loop, sem reagir a
   "caiu" — é a lição do NOC (`E:\Claude\LESSONS-NOC.md`), e há teste travando.
6. **Indisponível é resposta.** Gate que não rodou é `UNAVAILABLE`, não PASS.
   Stub verde ensinando que funcionou é pior que dizer "ainda não".
7. **Risco HIGH não roda sem confirmação humana.** `autoApproveHighRisk` em
   `harness.json` é `false` e não muda sem motivo escrito.
8. **Degradação é parcial, nunca all-or-nothing.** Ollama fora → determinístico
   segue. Qdrant fora → o que não usa RAG segue. Host fora → a tela diz o quê e
   por quê, com o erro real.
9. **NÃO criar outra app.** Evoluir esta. Se houver limitação arquitetural que
   torne isso impossível, documentar com evidência antes de mudar de direção.

## O que o modelo local erra (e como o sistema responde)

Aprendido rodando contra `qwen2.5-coder:14b`. Não são bugs do modelo — são
propriedades dele, e o sistema tem que absorver cada uma.

| Ele faz isto | O sistema responde |
|---|---|
| escreve a chamada de tool como TEXTO, sem usar `tool_calls` | `OllamaPlanner.toolCallsInText` reconhece a forma; o nome ainda passa pelo registry |
| preenche medida que ninguém deu | guarda determinística barra e devolve proposta |
| chuta `room_id` sem nunca ter listado cômodos | descrições mandam usar `find_object`, que varre a planta inteira |
| conclui "não existe" a partir de lista cortada | resultado é COMPACTADO (id/room/locked), nunca truncado |
| força o pedido nas tools que tem | capability inexistente é tool que recusa com motivo |
| resume errado o que acabou de fazer | operação de um significado só (`undo`, `restore`) tem resumo escrito pelo Harness |

**Regra que sai disso:** quando o modelo erra de um jeito que muda o projeto, a
correção é determinística. Prompt só para o que é preferência de estilo.

## Source of truth (não duplicar)

| Assunto | Arquivo |
|---|---|
| configuração (portas, modelo, caminhos, política) | `harness.json` — lido pelo Java **e** pelo Python |
| onde fica a raiz do Harness para o app EMPACOTADO | `-DharnessHome` (ver `packaging/build-app.cmd`) |
| schema das capabilities | `capabilities/harness_caps/registry.py` (o Java só lê) |
| estado do projeto (cena, edições, travas) | `state-local/<projeto>.scene.json` |
| envelope de trace | `inspector.domain.TraceEvent` |
| contrato alvo (tool registry, verificação, contexto) | `docs/HARNESS_MASTER_ORCHESTRATOR.md` |
| o que existe de verdade, com evidência | `docs/CAPABILITY_MATRIX.md` |
| onde o código diverge do contrato | `docs/review/SLICE1_ARCHITECTURAL_REVIEW.md` |
| o que implementar, em ordem | `docs/CODEX_QUEUE.md` |
| como a decisão foi tomada (inventário original) | `docs/HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md` |
| onde estamos agora | `HANDOFF.md` |
| histórico por fatia | `ITERATIONS.md` |
| gates, correction loop, RAG, pipeline | repo `sketchup-mcp` (dependência) |

## Convenções de código

- **`final var`** em variável local; **`final`** em parâmetro, `catch` e
  `for`-each; **`this.`** explícito em acesso a campo. Vale para o repo inteiro.
  Cuidado ao automatizar: `var x = new ArrayList<>()` infere `ArrayList<Object>`
  e `long x = 5` infere `int` — diamante e literal numérica vão à mão.
  Componente de `record` **não** aceita `final`.
- Domínio sem framework e sem I/O. Adapter em `*.source`, fronteira em
  `*.projection`, port declarado no domínio.
- **Zero CDN** e **`JSObject` proibido** (deprecated/removal). A ponte é
  `executeScript()`; a UI **enfileira** e o Java **puxa** (`drainRequests`).
- Teste nomeia COMPORTAMENTO. A suíte roda sem SketchUp, sem Ollama e sem
  Python — dublê para tudo que é externo.

## Rodar

O jeito do Felipe: **atalho `Harness App` na área de trabalho**. Ele aponta para
a pasta `app\rN` mais recente e é repontado por `packaging/build-app.cmd`.

```bash
# regenerar o app clicável (UI + testes + jpackage + atalho)
cmd //c "packaging\build-app.cmd"

# app, a partir do repo
./mvnw javafx:run

# o mesmo control plane, sem janela (prova reproduzível / diagnóstico)
java -cp "target/classes;$(cat target/cp.txt)" harness.cli.HarnessCli "move a escrivaninha 30 cm para a esquerda"

# testes
./mvnw test                                    # Java  (JAVA_HOME = JDK 25)
cd capabilities && python -m pytest            # Python (venv do pipeline)

# UI (o bundle é versionado; javafx:run não precisa de npm)
cd ui && npm run build
```

⚠️ `JAVA_HOME` **precisa** ser o JDK 25 (`maven.compiler.release=25`). Com o
JDK 21 o surefire falha com "class file version 69.0".

⚠️ **Capability nova só aparece reabrindo o app.** A tabela de tools é lida UMA
vez, quando o capability host sobe.

⚠️ `packaging/build-app.cmd` é **ASCII puro** e usa `cd` absoluto, não
`pushd`/`popd` — o shim do npm desbalanceia a pilha de diretórios do cmd e o
passo seguinte não achava o `mvnw.cmd`. Um travessão no arquivo também já quebrou
o parser.

## Gotchas herdados (detalhe em `docs/field-notes-jpackage.md`)

- App-image do jpackage é **imutável** e **nunca reutiliza caminho já apagado**.
  Sanidade = **dois processos**, o segundo com ~290 MB.
- Emoji não renderiza no WebView do JavaFX.
- `preview_start` lê o `launch.json` do **workspace**, não o do app. Porta deste
  app é **5199** (5173 é do System Design Lab).
