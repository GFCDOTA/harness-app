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
