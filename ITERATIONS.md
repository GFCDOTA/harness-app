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
