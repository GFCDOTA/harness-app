# ITERATIONS — harness-app

Uma entrada por fatia. Curto: o que mudou, o que provou, o que quebrou, o que foi
decidido. Não é diário.

---

## Slice 1 — Master Control Minimum · 2026-09-19

**Objetivo:** provar o caminho completo, do comando em português até a alteração
validada e desfazível. Menor fatia que transforma o Harness de observador em
orquestrador.

**Mudança**
- Capability plane em Python (`capabilities/harness_caps/`): cena persistida,
  tool registry tipado, gates sobre a edição, host NDJSON.
- Control plane em Java (`harness.*`): agent runtime governado, registry, planner
  Ollama, service manager, trace no envelope v1, CLI.
- UI: aba Agente com barra de comando.
- Passe de estilo em toda a app: `final var`, `final` em parâmetro/catch/for-each,
  `this.` explícito.

**Resultado** — executado de verdade contra Ollama e o pipeline:
`"move a escrivaninha 30 cm para a esquerda"` → `find_object` → `move_object`
(6 peças) → gates automáticos → `GATE_FAILED` (circulação) → `"desfaz"` → `CLEAN`,
0 edições pendentes. Trace de 11 eventos com parentesco de span.

**Testes:** 165 Java (baseline 103) + 68 Python. A falha do
`ImplementationCatalogTest` continua sendo descasamento de branch do pipeline.

**Problemas encontrados (todos reais, todos em execução de verdade)**
1. `qwen2.5-coder:14b` escreve a chamada de tool dentro de `content` em vez de
   usar `tool_calls`. O comando terminava `ANSWERED` com um blob de JSON como
   resumo e **zero** tool executada.
2. `Map.copyOf` rejeita valor `null`, e o host devolve `"unique": null` de
   propósito. Mesmo gotcha já documentado em `TraceEvent.meta`.
3. `find_object` bem-sucedido contava como "mudou o sistema" — "onde está a
   mesa?" terminava `CLEAN`. Passou a sair do `mutates` declarado na tool.
4. O artigo em "a escrivaninha" quebrava o casamento exato e a busca caía no
   substring, onde `cadeira_escrivaninha` também casa. O agente pedia
   desambiguação numa frase sem ambiguidade.
5. O resumo de um `undo` ficava por conta do modelo, que escreveu "A escrivaninha
   foi movida 30 cm para a esquerda" **como resumo de um desfazer**. Dados certos,
   manchete mentindo.
6. `circulation_gate` não usa `fails`/`warns`; devolve `checks` aninhado. Um FAIL
   chegava sem motivo na tela.
7. O nome visível do objeto vinha do rótulo da primeira peça: "top movida 300 mm".
8. `ServiceManagerTest` levava 60 s reais porque só o sono era injetado, não a
   janela de espera.

Cada um tem teste de regressão.

**Decisão**
- Capability plane em Python, onde os gates vivem. Schemas publicados por um lado só.
- Host como **processo filho** NDJSON, não serviço HTTP.
- Cena = baseline determinística + log de edições invertíveis.
- Agente grava no envelope v1 do Inspector — um contrato de trace, não dois.
- `startAll`/`restartFailed` como **ação**, nunca reflexo (lição do NOC), com
  teste travando a ausência de watchdog.
- Capability inexistente é publicada como `unsupported` com motivo.

**Achado sobre o projeto, não sobre o código:** a `SUITE 01` já reprova circulação
na baseline — um portal PRIMARY com 1,00 m livre vazio vai a 0,00 m com a mobília
atual. Apareceu porque o Harness passou a medir; vale decidir o que fazer.

### Delta — uso real do Felipe, mesma sessão

Ele abriu o app e escreveu **"abra a ultima planta feita do sketchup"**. Duas
coisas apareceram, as duas reais:

1. **Não existia capability para isso.** O modelo improvisou `restore_last_clean`
   (que recusou corretamente, dizendo que nenhuma versão CLEAN foi marcada) e
   depois `open_project` — que carrega o documento de cena, não abre o arquivo no
   SketchUp. Pedido legítimo, buraco real no registry.
2. **O desfecho apareceu como VALIDADO.** `open_project` muta e nenhum gate
   rodou, então `CLEAN` estava certo; o **rótulo** é que afirmava o que ninguém
   verificou. Numa ferramenta cuja regra é "o veredito vem do gate", isso é
   grave. Agora `CLEAN` só lê "validado" quando um gate rodou; sem gate, "feito".

**Adicionado:** `list_skp_artifacts` e `open_skp_in_sketchup`. Ordena por data de
escrita, nunca por nome — arquivo com sufixo de tema ordena antes do canônico em
ordem alfabética. `scene.skp` fica de fora por regra do projeto, e ele é
justamente o mais recente: ordenar por data sem excluir erraria toda vez.

**Lição de processo:** a primeira coisa que ele pediu ao produto não estava no
registry. Vale olhar o que ele tenta e não consegue antes de escolher a próxima
fatia pela lista do plano.

### Delta — "ela tá mt burra ainda"

Felipe operou o app e os três primeiros comandos expuseram falhas de desenho. A
mais grave: **"altere a cama dos quartos" virou `move_object(forward, 100mm)`**.
Ele não disse direção nem distância; o modelo preencheu as duas lacunas, os gates
aprovaram (mover 10 cm não quebra nada) e o projeto mudou.

Isso obrigou a nomear uma regra que estava implícita e errada: **gate verde não
valida alteração inventada.** O gate responde "isto é válido?", não "foi isto que
pediram?". São perguntas diferentes e eu tinha tratado como a mesma.

Correções, todas determinísticas onde o erro muda o projeto:

1. `AgentRuntime.fabricatedMeasurement` — medida que não aparece no comando não
   vira alteração; volta como proposta para confirmação.
2. As 12 capabilities inexistentes viraram **tools registradas que recusam** com
   `NOT_IMPLEMENTED`. Tentei primeiro por prompt ("não tente contornar") e o
   modelo recaiu na mesma sessão: ele escolhe tool por nome e ignora proibição em
   prosa. Registrada, a tool casa com a intenção e devolve o motivo.
3. Resultado de tool passou a ser **compactado, não truncado**. Cortar
   `list_objects` nos 12 primeiros fez o modelo responder "não encontrei nenhuma
   cama nos quartos" — em ordem alfabética os 12 primeiros são todos da área de
   serviço. Dado incompleto produz conclusão errada com toda a confiança.

**Lição de método:** prompt é pedido, não garantia. Serve para preferência de
estilo; não serve para impedir o modelo de alterar o projeto.
---

## Slice 1.5 - Capability Lookup + Verification Contract - 2026-09-19

**Objetivo:** separar `CAPABILITY` de `EXECUTION` e impedir falso sucesso quando
uma capability ainda nao existe ou quando uma escrita nao prova o efeito.

**Mudanca**
- `ToolSpec` agora publica `implemented`, `verification` e `outputSchema` pelo
  registry Python; o Java consome esses campos do capability host.
- Capability publicada como `implemented=false` para no lookup com
  `CAPABILITY_MISSING`, antes de validar schema. `set_material({object_id,color})`
  nao vira mais `INVALID_ARGUMENTS`.
- `AgentRuntime` grava `capability.lookup` e `tool.verified` no envelope v1.
- `move_object` declara `STATE_DELTA`; o runtime compara `bboxBefore/bboxAfter`
  contra direcao/distancia esperadas. Falha vira `UNVERIFIED`, nao `CLEAN`.
- `get_agent_info` virou capability deterministica (`provider/model/mode/url`).
- UI reconhece `UNVERIFIED` como "nao verificado".

**Testes:** Python 78 passed. Java 171 testes: 170 passed + 1 falha preexistente
em `ImplementationCatalogTest` por descasamento do repo `sketchup-mcp`
(`_faceted_rank`, `core/observability/context.py`, `run_scope`).

**Smoke manual:** registry Python com cena temporaria: `get_agent_info` ok,
`set_material(object_id,color)` -> `CAPABILITY_MISSING` com `executed=false`,
`move_object(left,100mm)` gerou delta `dxIn=-3.937008` e history com 1 edicao.

**Limitacao:** a verificacao de `move_object` ainda prova a cena editada, nao o
`.skp` aberto no SketchUp. Materializar em `.skp` continua sendo Slice 2
(`apply_to_skp`) e deve evitar o caminho com `taskkill /F /IM SketchUp.exe`.

---

## Slice 2 — `apply_to_skp`: a cena editada vira `.skp` (2026-09-20)

**Problema:** toda a operacao morria no documento de cena. O `.skp` que o Felipe
abria era o de 2026-08-09, indiferente a qualquer comando dado. O slice 1 operava
um MODELO da planta, nao a planta. Este slice fecha o criterio 5 da fase 1.

**Mudanca**
- `Pipeline.materialize(boxes, out_path, close_sketchup, timeout_sec)` roda o
  SketchUp em lote sobre `place_layout_skp.rb` e devolve a evidencia do artefato.
- Os boxes saem do `SceneStore` (baseline + edits), NUNCA do cerebro de layout —
  recomputar entregaria o layout original e a fatia inteira seria teatro. Ha
  teste travando isso pelo conteudo do env `LAYOUT_BOXES`.
- `SketchUpRunner` isola processo e relogio; a suite roda sem SketchUp instalado.
- `apply_to_skp` saiu de `UNSUPPORTED` e virou tool real: `verification=ARTIFACT`,
  `risk=MEDIUM`, `mutates=true`, `undoable=false`, timeout do gate.
- `verifyArtifact` no `AgentRuntime` — `ARTIFACT` nao tinha verificador e caia no
  fail-closed, entao a capability nunca reportaria sucesso.
- `SceneStore.mark_materialized` / `unmaterialized_edits`; `get_project_state`
  passa a publicar `unmaterializedEdits` e `lastMaterialized`.

**Decisoes que nao se reabrem sem motivo escrito**
- Destino FORA do repo `sketchup-mcp` (ele e dependencia de leitura) e nunca o
  `.skp` canonico. Alvo dentro do repo e recusado sem subir o SketchUp.
- Apagar o destino ANTES de rodar: e o que torna "existe e tem tamanho" prova de
  que ESTA execucao escreveu, e nao resto de uma anterior.
- `.skp` de 0 byte -> `verified=false`. E a falha classica do SketchUp em lote.
- SketchUp aberto -> RECUSA (`SKETCHUP_BUSY`, `needsHumanDecision=true`). O
  padrao do pipeline e `taskkill /F`, que mataria a janela do Felipe com trabalho
  possivelmente nao salvo. Fechar exige `close_sketchup=true`.
- `ARTIFACT` sem o campo `verified` -> UNVERIFIED. Sucesso por omissao e
  exatamente o que a hard rule #6 proibe.

**Testes:** Python 93 passed (15 novos). Java 173 passed, 0 falhas (2 novos).

Nota: a falha preexistente em `ImplementationCatalogTest`, registrada no slice
1.5 como descasamento com o repo `sketchup-mcp` (`core/observability/context.py`),
**desapareceu** — o modulo entrou no `develop` do pipeline pelo merge da PR #246
(subsistema de observabilidade) em 2026-09-20.

**Limitacao declarada:** toda a fatia esta coberta por dubles. Isso prova a
fiacao e a honestidade do resultado, mas NAO prova que o `place_layout_skp.rb`
aceita estes boxes e produz um `.skp` abrivel. So fecha rodando o SketchUp de
verdade, e o veredito e VISUAL — do Felipe. Registrado no `CODEX_QUEUE.md`.

---

## Slice 4 — `set_color`: o pedido que originou o projeto (2026-09-20)

**Problema:** "troca a cor da cama para preto" era o comando que motivou o
Harness inteiro, e ate hoje devolvia `set_material -> NOT_IMPLEMENTED`.

**Mudanca**
- `colors.py` — tabela FECHADA de 27 cores + sinonimos em ingles. Nome fora da
  tabela RECUSA com a lista; nunca chuta um RGB plausivel.
- `SceneStore.recolor` + op `recolor` no `_apply`. Mesma fila do `translate`,
  entao undo/redo saem de graca e `unmaterializedEdits` conta cor junto com
  movimento — cor tambem precisa de `apply_to_skp` para chegar no arquivo.
- Tool `set_color`: `STATE_DELTA`, `risk=MEDIUM`, `undoable=true`, `mutates=true`.
- `verifyColorDelta` no Java: `STATE_DELTA` estava amarrado a `move_object`, e
  `set_color` cairia no fail-closed. Handler que diz ter pintado e nao mudou o
  `rgb` vira UNVERIFIED — o caso que o CODEX_QUEUE pedia explicitamente.

**A armadilha que quase fez a fatia ser falsa**

`place_layout_skp.rb:44` faz `m = model.materials[name]; return m if m`. Material
e reusado **pelo NOME** e o `rgb` de quem chega depois e **IGNORADO**. Trocar a
cor mantendo o `ph_<kind>` compartilhado produziria um `.skp` visualmente
identico — e todos os testes de cena passariam. Descoberto lendo o builder ANTES
de escrever o handler, nao depois.

Solucao: `mat_name` proprio por objeto+cor (`harness_<objectId>_<hex>`),
deterministico e estavel (repintar da mesma cor da o mesmo nome) e disjunto entre
objetos (duas pecas pretas nao colidem). Ha teste para cada uma dessas tres
propriedades, e um teste de costura que le o `LAYOUT_BOXES` que o builder
receberia e confere rgb + mat_name.

**Decisao sobre o enum do schema**

O enum lista canonicos E sinonimos. So os canonicos faria o schema rejeitar
`black` — alias valido — e a tabela de sinonimos viraria peso morto. Com o enum
completo, cor inventada para em `INVALID_ARGUMENTS` antes de tocar na cena, e o
erro carrega o enum: o modelo se corrige sozinho. `UnknownColor` continua no
handler como defesa em profundidade, com teste provando que nao e codigo morto.

**Gate de geometria NAO roda** para cor — cor nao move nada. `gatesRun: false`
sai explicito no resultado, em vez de omitir e deixar parecer que rodou.

**Testes:** Python 109 passed (17 novos). Java 175 passed, 0 falhas (2 novos).

**Limitacao declarada:** como no slice 2, tudo e duble. Nenhum `.skp` real foi
gerado. O fechamento honesto e abrir o arquivo e olhar — veredito do Felipe.

---

## Rodando o app DE VERDADE — 4 defeitos que 184 testes verdes nao pegaram (2026-09-21)

Pedido do Felipe: "faz testes com a app harness, pede pra ela alterar algumas
coisas na planta e verifica se esta fazendo corretamente". Cinco comandos em
portugues contra a planta_74, com Ollama vivo e SketchUp em lote.

**O que a fatia de capabilities entregou, provado:** `set_color` + `apply_to_skp`
geraram um `.skp` real (543 KB, 404 boxes, 18s). Um inspetor Ruby read-only
confirmou DENTRO do arquivo: material `harness_suite_01.cama_1a1a1c` com
`rgb=(26,26,28)`, aplicado a **9 entidades** — as 9 pecas da cama. Os 179
materiais `ph_*` compartilhados ficaram intactos, o que prova que o nome proprio
era mesmo necessario. A divida "nenhum .skp real foi gerado" esta FECHADA.

**O que quebrou foi o PLANNER, e nada disso aparecia em teste com duble:**

1. **Laco em tool que muda estado.** "desfaz a ultima alteracao" -> `undo` 4x ->
   7 edicoes desfeitas. Mutacao identica agora bloqueada com `ALREADY_APPLIED`.
2. **`EXHAUSTED` mentindo.** Desfecho dizia "sem chegar a um desfecho" para
   comando cuja alteracao foi aplicada E verificada.
3. **Modelo contrariando o comando.** "trinta centimetros para a ESQUERDA" ->
   `direction=right, distance_mm=100`, aplicado. `contradictsCommand` barra.
4. **Falso negativo do slice 4** (meu): verificador de cor comparava o relido com
   o ANTERIOR, reprovando repintura idempotente.

Todos reproduzidos em teste ANTES do fix e re-verificados no app real DEPOIS.
Java 184, Python 109.

**A licao que fica:** a suite roda sem Ollama de proposito, e isso e certo — mas
significa que ela nao cobre o planner. Rodar o `HarnessCli` contra o modelo real
faz parte de "esta funcionando". Tabela de falhas do modelo atualizada no
`CLAUDE.md`.

**Aberto, nao corrigido:** distancia errada com direcao certa ainda passa (30 cm
-> 100 mm) — parsing de medida em portugues livre da falso positivo facil e
precisa de desenho proprio; o modelo alucina `error_response` (registry recusa,
sem dano); e ignora o comodo nomeado quando o objeto resolve unico em outro lugar.
