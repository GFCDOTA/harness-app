# HANDOFF — harness-app

> Onde estamos, agora. Quem pegar isto não deve precisar do histórico da conversa.

**Atualizado:** 2026-09-19 · **Branch:** `develop` ·
**Slice 1 MERGEADO** via [PR #1](https://github.com/GFCDOTA/harness-app/pull/1) (`3e1cf06`)

---

## Mapa dos documentos

| Quer saber | Leia |
|---|---|
| as regras que não se quebra | [`CLAUDE.md`](CLAUDE.md) |
| o contrato alvo | [`docs/HARNESS_MASTER_ORCHESTRATOR.md`](docs/HARNESS_MASTER_ORCHESTRATOR.md) |
| o que existe de verdade | [`docs/CAPABILITY_MATRIX.md`](docs/CAPABILITY_MATRIX.md) |
| onde o código diverge do contrato | [`docs/review/SLICE1_ARCHITECTURAL_REVIEW.md`](docs/review/SLICE1_ARCHITECTURAL_REVIEW.md) |
| o que implementar, em ordem | [`docs/CODEX_QUEUE.md`](docs/CODEX_QUEUE.md) |
| como a decisão foi tomada | [`docs/HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md`](docs/HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md) |
| histórico por fatia | [`ITERATIONS.md`](ITERATIONS.md) |

## COMO RETOMAR (sessão nova começa aqui)

**O slice 1 está em `develop`.** Não há PR aberta e não há branch de feature viva.

**Ação pendente: slice 2 — `apply_to_skp`**, em
[`docs/CODEX_QUEUE.md`](docs/CODEX_QUEUE.md). É fiação: `place_layout_skp.rb` já lê
`LAYOUT_BOXES`/`LAYOUT_OUT`. É a fatia que faz a alteração finalmente chegar no
`.skp` — hoje o slice 1 opera um MODELO da planta, não a planta.

### Estado verificado em 2026-09-19

| Item | Estado |
|---|---|
| `develop` local × origin | idênticos, `3e1cf06` |
| PR #1 | **MERGED** 16:11 UTC · 20 commits · 101 arquivos |
| branches de feature | **nenhuma** — as 4 mergeadas foram deletadas (remote + local) |
| CI | **não existe** — nenhum check configurado no repo |
| suítes | 171 Java (1 falha pré-existente) · 78 Python |
| `develop` × `main` | develop **33 commits à frente**; `main` não recebeu nada |

A pilha era linear (`master-orchestrator` ⊂ `slice1` ⊂ `slice1-impl`), então a PR #1
levou as três de uma vez.

### Branch nova sai de `origin/develop`

Não trabalhar direto em `develop`. Para o slice 2:
`git checkout -b feat/harness-slice2-apply-skp origin/develop`.

### ⚠️ Auth do `gh` mudou em 2026-09-19 — não voltar pro `GH_TOKEN`

Agora é **keyring** (`gh auth login`, device flow). Token `gho_*`, scopes
`gist, read:org, repo, workflow`. `GH_TOKEN` foi **apagado** do registro do usuário.

**`GH_TOKEN` tem precedência sobre o keyring.** Se ela reaparecer, o `gh auth login`
roda mas o gh segue usando o valor velho. E processo que nasceu antes da limpeza
**ainda carrega a variável** — numa sessão do Claude Code é preciso
`Remove-Item Env:GH_TOKEN` antes de chamar o gh.

O que custou várias sessões, para não re-descobrir:

- O PAT fine-grained em uso tinha **resource owner = conta pessoal**
  (`fmodesto30`), e os repos são da **org GFCDOTA**. Fine-grained só alcança
  recursos do próprio owner → o token não tinha acesso a repo nenhum, e a página
  dele **nem mostra** dropdown de Pull requests pra consertar. Mexer em permissão
  ali nunca ia funcionar.
- **`git push` mascarou tudo**: push não lê `GH_TOKEN`, vai por
  `credential.helper=manager` (Windows Credential Manager). "Push funciona mas PR
  dá 403" parecia permissão de PR faltando; o token estava vazio.
- Repo **público** responde leitura a qualquer token. `gh api repos/...` retornar
  dados **não prova** que o token serve. Probe honesto de PR-write = tentar criar.

Detalhe completo na skill `gh-autopilot` do `sketchup-mcp`.

### Higiene pendente

PATs antigos em **https://github.com/settings/personal-access-tokens** — incluindo
o token `claude` (vazio, sem uso) e o que foi **colado em texto puro no chat**.
**Revogar todos**, já que a auth agora é pelo keyring.

---

## CURRENT STATE

O Harness virou o Control Plane e o slice 1 roda de ponta a ponta — **sobre o
documento de cena**. Um comando em português entra, o modelo local escolhe a
capability, o Harness executa, os gates rodam sozinhos, o trace é gravado no
envelope que o Inspector lê, e dá para desfazer.

**A ressalva que muda a leitura de tudo:** nenhuma alteração chega ao `.skp`.
`apply_to_skp` é MISSING. O `.skp` que o Felipe abre é o de 2026-08-09,
indiferente a qualquer comando dado. O slice 1 opera um MODELO da planta, não a
planta.

## ARCHITECTURE

Quatro estágios — `UNDERSTANDING → CAPABILITY → EXECUTION → VERIFICATION`.
Hoje existem **dois** (entender, executar), e é daí que sai o sintoma
`troca a cor da cama → apply_to_skp NOT_IMPLEMENTED`: `NOT_IMPLEMENTED` aparece
como resultado de execução quando deveria ser resposta de lookup.

```
UI → ControlPlane → AgentRuntime → LlmPlanner (Qwen/Ollama)
                         ↓
                   ToolRegistry  ← governança: risco, schema
                         ↓
             CapabilityHost (processo filho Python, NDJSON)
                         ↓
         SceneStore · gates · finding_router · sketchup-mcp (leitura)
```

Planos: `harness.*` = controle · `inspector.*` = observabilidade. Falam por um
contrato só, o envelope v1, travado por `AgentTraceContractTest`.

**Não existe serviço vivo de SketchUp** — é invocação em lote. Qualquer desenho
que suponha o contrário está errado sobre esta máquina.

## CAPABILITIES

Detalhe e evidência em [`docs/CAPABILITY_MATRIX.md`](docs/CAPABILITY_MATRIX.md).
Placar: **11 READY · 7 PARTIAL · 21 MISSING**.

**READY** — `open_project`, `get_scene`, `list_objects`, `find_object`,
`get_object`, `undo`, `redo`, `snapshot`, `list_history`, `run_gates`,
`get_findings`, `status` de serviço.

**PARTIAL** — `move_object` (só a cena; gates só do cômodo de origem) ·
`open_sketchup` (não verifica) · `save_project` (não grava `.skp`) ·
`restore_last_clean` (nada marca CLEAN automaticamente) · `get_room` (sem
polígono) · `start`/`start_all`/`restart_failed` (sem botão na UI).

**MISSING** — `apply_to_skp` · `set_color`/`set_material`/`set_texture` ·
`rotate`/`resize`/`align` · `create`/`delete`/`duplicate` ·
`run_correction_loop` · `render`/`visual_review`/`set_camera` · RAG ·
`close_sketchup` · `get_selection`.

Três MISSING são **fiação**, não construção — a peça pesada já existe do outro
lado: `apply_to_skp` (`place_layout_skp.rb` lê `LAYOUT_BOXES`),
`run_correction_loop` (`run_loop()` aceita `boxes=`), `set_color`
(`recolor_kitchen_theme.rb` prova o padrão).

## CODEX QUEUE

Fila completa em [`docs/CODEX_QUEUE.md`](docs/CODEX_QUEUE.md).

```
1.5  separar CAPABILITY de EXECUTION + verificação   ← PRIMEIRO, sem exceção
2    apply_to_skp — a cena vira .skp
3    service control na UI (backend pronto)
4    materiais e cor — o pedido do Felipe
5    agent loop com constraints
6    visual
```

## BLOCKERS

1. **B1 — a tool que recusa valida argumento antes de dizer que não existe.**
   Reproduzido: `set_material({object_id, color})` → `INVALID_ARGUMENTS`;
   `set_material({})` → `NOT_IMPLEMENTED`. Causa mecânica do sintoma relatado.
2. **B2 — nenhuma capability verifica execução.** Sucesso = "o handler
   retornou". `open_skp_in_sketchup` reporta sucesso na linha seguinte ao
   `Popen`.

Os dois caem no slice 1.5.

**Atenção operacional:** `furnish_apartment.py` dá `taskkill /F /IM
SketchUp.exe`. Qualquer slice que reuse esse caminho **mata a janela aberta do
Felipe**. Decidir antes de implementar o slice 2.

**Fora do ar:** Qdrant e GPT-Docker. Não impedem 1.5–4; impedem RAG e veredito
visual.

## DECISIONS (não reverter sem razão escrita)

1. Harness é o Master Orchestrator. Claude/Codex não são dependência de runtime.
2. O modelo não toca o sistema — só tool declarada, argumento validado.
3. O veredito é do gate, não do texto do modelo; alteração geométrica dispara
   gate automaticamente.
4. **Gate verde não valida alteração inventada** — "é válido?" ≠ "foi isto que
   pediram?".
5. **Capability inexistente é tool REGISTRADA que recusa com motivo.** Modelo
   escolhe tool por nome e ignora proibição em prosa — testado, ele recaiu.
6. Nada ressuscita sozinho. Botão sim, watchdog nunca.
7. Um envelope de trace só, compartilhado com o Inspector.
8. Schema publicado por um lado só (Python).
9. Risco HIGH exige confirmação humana.
10. Capability plane em Python, onde os gates vivem; host é processo FILHO.

## TEST BASELINE

| Suíte | Resultado |
|---|---|
| Java (`./mvnw test`) | **170**, 1 falha **pré-existente** |
| Python (`cd capabilities && python -m pytest`) | **77 passed** |

A falha pré-existente (`ImplementationCatalogTest`) é descasamento de **branch**
do repo do pipeline: `core/observability/context.py` e `_faceted_rank` estão em
`feat/ai-pipeline-inspector-observability`, não na branch que está na pasta. Não
é regressão. Conserto real: landar a observabilidade na develop do pipeline.

Nada na suíte precisa de SketchUp, Ollama ou Python. **A fronteira entre os dois
repos não tem cobertura nenhuma** — é onde mora a dependência de `_module_geom`.

## NEXT STEP

Slice 1.5. Começar por `Registry.invoke` em
`capabilities/harness_caps/registry.py`: mover a checagem de `implemented` para
antes da validação de schema. É o menor diff que fecha o BLOCKER B1 e o teste de
aceitação já está escrito na fila.

---

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

## Correções vindas do uso real (2026-09-19)

O Felipe operou o app e as três primeiras coisas que ele pediu expuseram falhas
de desenho. Estão corrigidas e travadas por teste:

| O que ele digitou | O que acontecia | O que acontece agora |
|---|---|---|
| "altere a cama dos quartos" | virava `move_object(forward, 100mm)` — medida inventada, gates aprovaram, projeto mudou | guarda determinística barra; volta como proposta para confirmar |
| "coloque um lençol preto" | `find_object("o lencol preto")` → "não encontrei esse objeto" | `NOT_IMPLEMENTED` dizendo que material não está implementado |
| "altere a cama dos quartos" | achava 1 cama (lista truncada + `room_id` chutado) | `find_object` acha as DUAS e pergunta qual |
| "abra a última planta" | não existia capability | `open_skp_in_sketchup` abre a mais recente |
| qualquer comando sem gate | selo dizia **VALIDADO** | diz "feito"; "validado" só com gate |

**A lição que fica:** o modelo não erra por burrice, erra por propriedade. Quando
o erro dele muda o projeto, a correção tem que ser determinística — prompt é
pedido, não garantia. Tentei corrigir o caso do lençol por prompt e ele recaiu na
mesma sessão; só parou quando a capability inexistente virou tool que recusa.

## O que ainda NÃO funciona

0. **Reiniciar o app depois de mexer no registry.** A tabela de capabilities é
   lida UMA vez, quando o capability host sobe. Capability nova só aparece na
   próxima abertura da janela.
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
# Slice 1.5 update - 2026-09-19

Branch de implementacao: `feat/harness-master-slice1-impl`.

Implemented:
- Tool registry publishes `implemented`, `verification`, `outputSchema`.
- Missing capability returns `CAPABILITY_MISSING` during lookup, before argument
  validation. Tested case: `set_material({object_id,color})`.
- Runtime records `capability.lookup` and `tool.verified` in trace v1.
- `move_object` uses `STATE_DELTA` verification over `bboxBefore/bboxAfter`;
  verification failure becomes `UNVERIFIED` and does not run gates.
- `get_agent_info` is deterministic (`ollama`, configured model, local mode).
- UI labels `UNVERIFIED` as "nao verificado".

Tests:
- Python capabilities: `78 passed`.
- Java: `171` tests, `170 passed`, `1` pre-existing failure in
  `ImplementationCatalogTest` due to `sketchup-mcp` checkout divergence
  (`_faceted_rank`, `core/observability/context.py`, `run_scope`).
- UI: `npm run build` OK.
- Temporary smoke: material request returns `CAPABILITY_MISSING` without
  execution; move 100 mm left produced `dxIn=-3.937008` in isolated scene state.

Current limitation:
- Move verification still proves the scene document, not the real `.skp`.
  `apply_to_skp` remains the next slice; do not reuse the path with
  `taskkill /F /IM SketchUp.exe` without a safe adapter.
