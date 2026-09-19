# Revisão arquitetural — slice 1 do Master Orchestrator

**Revisor:** Lead Architect · **Data:** 2026-09-19
**Alvo:** `feat/master-orchestrator` @ `e6d6b00`
**Referências:** [`CLAUDE.md`](../../CLAUDE.md) ·
[`HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md`](../HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md) ·
[`HANDOFF.md`](../../HANDOFF.md)

---

## Nota sobre o alvo da revisão

A branch pedida — `feat/harness-master-slice1` — **não existia** no momento desta
revisão. Verificado em: refs locais, `git ls-remote origin`, `git worktree list`,
e as branches de todos os repos em `E:\Claude\apps\`. `docs/HARNESS_MASTER_ORCHESTRATOR.md`
também não existia (só o `_KICKOFF`). Nenhum commit do Codex é alcançável.

O que foi revisado, portanto, é a implementação que **está rodando** —
`feat/master-orchestrator` — que é de onde vem o sintoma relatado
(`troca a cor da cama` → `apply_to_skp NOT_IMPLEMENTED`).

Esta é uma **auto-revisão**: o código sob análise é meu. Foi conduzida com o
viés oposto ao de confirmação — procurando onde o sistema afirma mais do que
prova.

---

## Achado central

> **O slice 1 opera um DOCUMENTO DE CENA, não o modelo do SketchUp.**

Tudo funciona como documentado, e ainda assim nenhuma alteração chega ao `.skp`.
`move_object` edita `state-local/planta_74.scene.json`; `apply_to_skp` está em
`UNSUPPORTED`. O `.skp` que o Felipe abre pelo `open_skp_in_sketchup` é o de
2026-08-09, alheio a qualquer comando que ele tenha dado.

Os três documentos descrevem isso com graus diferentes de clareza, e é essa
diferença que produz o desalinhamento: o `HANDOFF.md` diz em uma linha do item 1
que a cena não vira `.skp`, mas a linha de abertura diz que o Harness "executa
sobre a cena real da `planta_74`". As duas são verdadeiras e juntas enganam.

Isso não é bug de implementação — é **fronteira de escopo mal sinalizada**, e
explica por que o sistema parece "burro": ele está certo sobre um mundo que não é
o que o Felipe está olhando.

---

## BLOCKER

### B1 — Tool que recusa rejeita os argumentos ANTES de dizer que não existe

**Onde:** `capabilities/harness_caps/registry.py` — `Registry.invoke()`, validação
antes do handler; `_register_unsupported()` declara `{"properties": {}}`.

As 12 capabilities pendentes são registradas com schema de entrada **vazio**. A
validação roda antes do handler, então qualquer argumento é recusado primeiro:

```
set_material({"object_id": "suite_02.cama", "color": "preto"})
  → INVALID_ARGUMENTS: argumento 'object_id' não existe nesta tool (aceita: nenhum)

set_material({})
  → NOT_IMPLEMENTED: material/textura vive em style_spec + LAYOUT_TEX_MAP…
```

**Reproduzido**, não inferido.

**Por que é BLOCKER:** destrói a própria razão de existir da hard rule 4 do
`CLAUDE.md` ("capability que não existe RECUSA com o motivo"). Um modelo que
chama `set_material` com argumentos — que é o comportamento CORRETO — recebe uma
mensagem que sugere que ele errou o argumento, e tenta de novo, e cai no teto de
tentativas. **É a causa mecânica do sintoma relatado:** o modelo acaba chamando a
única tool pendente cuja chamada sem argumento passa (`apply_to_skp`), e o Felipe
vê `apply_to_skp NOT_IMPLEMENTED` em resposta a "troca a cor da cama".

**Contrato violado:** hard rule 4 do `CLAUDE.md`.

**Direção de correção (não aplicar agora):** a checagem de "não implementado"
precisa vir ANTES da validação de schema — ou o schema dessas tools precisa
aceitar qualquer campo. A primeira é melhor: o motivo não depende do argumento.

---

### B2 — Nenhuma capability tem verificação de execução

**Onde:** `ToolSpec` (Java e Python) — os campos são `name`, `description`,
`input_schema`, `handler`, `risk`, `undoable`, `requires`, `timeout_sec`,
`mutates`. Não existe `output_schema` nem `verification`.

Consequência concreta, por categoria:

| Tool | O que prova sucesso hoje | O que deveria provar |
|---|---|---|
| `move_object` | o `SceneStore` aceitou a translação | o transform mudou onde importa |
| `open_skp_in_sketchup` | `Popen` não lançou | processo vivo + projeto certo carregado |
| `open_project` | `collect_boxes` retornou | idem |

`open_skp_in_sketchup` é o caso mais nítido: `pipeline.open_in_sketchup()` faz
`subprocess.Popen(...)` e devolve `{"opened": path}` na linha seguinte. Se o
SketchUp morrer no arranque, o Harness reporta sucesso.

**Por que é BLOCKER:** o `CLAUDE.md` afirma como hard rule 2 que sucesso exige
"estado mudou + validação rodou". Para tudo que não é geometria dentro do
documento de cena, essa afirmação **não é sustentada por nada**. Os gates cobrem
validade geométrica, não ocorrência.

**Contrato violado:** hard rule 2 do `CLAUDE.md`; §33 do briefing original
("sucesso não é 'o Qwen respondeu'" — hoje é "o handler não lançou").

---

## HIGH

### H1 — A guarda anti-invenção confere PRESENÇA de número, não correspondência

**Onde:** `AgentRuntime.fabricatedMeasurement`.

A guarda passa se o comando contém um dígito ou um número por extenso. Ela não
compara com o valor que o modelo preencheu. `"move a cama 10 cm"` seguido de
`move_object(distance_mm=1000)` passa — o comando tem dígito.

É meia-guarda: pega a lacuna total (nenhum número), não a distorção (número
errado). O `CLAUDE.md` hard rule 3 promete mais do que o código entrega.

### H2 — Gates rodam só no cômodo de origem

**Onde:** `AgentRuntime.runGates` — resolve UM `roomId`, do resultado da tool.

Um move que empurra um objeto para o cômodo vizinho valida o cômodo de onde ele
saiu e ignora aquele onde ele entrou. O `geometry` gate pegaria `outside_room`,
mas colisão e circulação do cômodo de destino não são medidas.

### H3 — `_module_geom` é API privada do pipeline e a dívida não está travada

**Onde:** `capabilities/harness_caps/pipeline.py` — `overlap_gate_on()`.

O docstring diz que a dependência é "dívida NOMEADA — travada por teste". **Não
existe esse teste.** Os testes de gate usam dublê (`tests/test_gates.py`), e
nenhum teste afirma que `tools.furniture_overlap_gate._module_geom` existe no
repo real. Um rename no pipeline quebra o gate de colisão em produção com a
suíte verde.

Um documento que promete uma garantia inexistente é pior que silêncio — a
próxima sessão confia nela.

### H4 — Slice 1 é apresentado como completo sem o elo que o torna observável

`HANDOFF.md` declara "slice 1 entregue e demonstrável". É verdade para o
documento de cena. Mas o critério de aceitação 5 do kickoff ("alteração real no
sistema") é satisfeito por "6 peças da escrivaninha transladadas na cena
persistida" — e cena persistida não é o sistema que o usuário observa.

**Drift:** o kickoff define o Harness como operador *da planta*; a entrega opera
*um modelo da planta*. A distância entre os dois é `apply_to_skp`.

---

## MEDIUM

### M1 — `request()` pode entrar em cascata de timeout

**Onde:** `StdioCapabilityHost.request()`.

Numa resposta atrasada, o laço descarta ids que não batem até o deadline. Se o
deadline expira durante o descarte, retorna `null` e a resposta órfã fica no
pipe para a próxima chamada consumir. Uma tool lenta pode degradar as seguintes.
Não observado em uso; é análise de código.

### M2 — Ambiguidade de cômodo não usa o cômodo ativo em `find_object`

`SceneStore.resolve_one` desempata por cômodo ativo, mas `find_object` (o
caminho que o modelo usa) devolve a lista crua. O contexto existe e não é
aproveitado no caminho mais quente.

### M3 — `AgentState.observe` confia em nomes de campo do payload

Acopla-se a `objectId`, `roomId`, `unique`, `locked`, `editCount`. Uma tool nova
que use outro nome perde o estado silenciosamente. Não há teste de contrato entre
as chaves que o Python emite e as que o Java lê.

### M4 — `knowsUnsupported` é chamado a cada comando

`AgentRuntime` chama no construtor, e o construtor roda por comando. Inofensivo
hoje (cópia de lista), mas o port sugere configuração, não repetição.

### M5 — `ITERATIONS.md` e `HANDOFF.md` divergem na contagem de capabilities

`HANDOFF` foi atualizado para 34 tools; a seção de contratos ainda descreve o
registry como "22 + 12 unsupported", que era o modelo anterior (antes de virarem
tools que recusam).

---

## LOW

- **L1** — `JsonlTraceRecorder` trunca arquivo existente; `runId` é timestamp com
  resolução suficiente, mas nada impede colisão em execução paralela.
- **L2** — `SPELLED_NUMBERS` inclui `"um "` com espaço à direita: `"mova um
  pouco"` conta como medida dada. Falso negativo da guarda.
- **L3** — `HarnessCli` imprime o corpo inteiro da resposta do Ollama como
  "detail" do health check — polui o terminal com JSON de 2 KB.
- **L4** — `docs/HARNESS_MASTER_ORCHESTRATOR_KICKOFF.md` nomeia
  `search_knowledge()` / `search_preferences()` como se existissem. **Não
  existem** com esses nomes; o real é `reference_db.retrieve()` e
  `reference_db.query()`.

---

## Contract violations (resumo)

| # | Contrato | Onde está escrito | Violação |
|---|---|---|---|
| B1 | hard rule 4 — capability inexistente recusa com motivo | `CLAUDE.md` | recusa por argumento antes de dizer que não existe |
| B2 | hard rule 2 — sucesso exige estado mudou + validação | `CLAUDE.md` | nenhuma tool verifica ocorrência |
| H1 | hard rule 3 — medida não dita não vira alteração | `CLAUDE.md` | confere presença, não correspondência |
| H3 | "dívida travada por teste" | `pipeline.py` docstring | o teste não existe |
| H4 | critério 5 — alteração real no sistema | kickoff §7 | cena persistida ≠ sistema observável |
| L4 | inventário sem inventar módulo | kickoff §2 | nomeia função inexistente |

---

## Architecture drift

1. **Documento de cena virou o sistema.** O `SceneStore` foi introduzido para
   permitir undo e comando contextual — decisão correta. Mas ele se tornou o
   destino final das operações, e não um estágio antes do `.skp`. A arquitetura
   alvo do kickoff tem `SceneStore · gates · sketchup-mcp` no mesmo nível; na
   prática o terceiro nunca é alcançado por escrita.

2. **`UNDERSTANDING / CAPABILITY / EXECUTION / VERIFICATION` não são camadas.**
   Hoje existem duas: entender (planner) e executar (registry). "Capability
   lookup" está fundido ao registry e "verification" não existe. Enquanto forem
   dois estágios, `NOT_IMPLEMENTED` continuará aparecendo como *resultado de
   execução* em vez de *resposta de lookup* — que é o que o Felipe viu.

3. **Verificação foi delegada aos gates por acidente.** Gates respondem "é
   válido?"; ninguém responde "aconteceu?". Como gates só cobrem geometria,
   qualquer capability não-geométrica (material, render, abrir arquivo) nasce sem
   verificação e sem ninguém notar.

---

## Missing tests

| Prioridade | Teste ausente | O que ele pegaria |
|---|---|---|
| ALTA | tool que recusa chamada **com argumentos** devolve `NOT_IMPLEMENTED` | B1 — o bug que o Felipe viu |
| ALTA | `_module_geom` e `pairwise_overlap` existem no pipeline real | H3 — rename silencioso |
| ALTA | contrato de chaves Python→Java (`objectId`, `roomId`, `editCount`…) | M3 |
| MÉDIA | move que cruza fronteira de cômodo roda gates nos DOIS | H2 |
| MÉDIA | guarda anti-invenção com número presente mas divergente | H1 |
| MÉDIA | `StdioCapabilityHost` com resposta fora de ordem / atrasada | M1 |
| BAIXA | `open_skp_in_sketchup` quando o executável morre no arranque | B2 (parcial) |

Nenhum teste de integração exercita o capability host real contra o pipeline
real. É defensável para CI (a suíte roda sem SketchUp/Ollama/Python, como manda
o `CLAUDE.md`), mas significa que **a fronteira entre os dois repos não tem
cobertura nenhuma** — e é exatamente onde H3 mora.

---

## Verification weaknesses

Ordenado por gravidade.

1. **Execução não é verificada em lugar nenhum.** O sinal de sucesso é "o handler
   retornou". Para `move_object` isso é quase aceitável (o `SceneStore` é a
   verdade daquele domínio); para todo o resto é presunção.
2. **`open_skp_in_sketchup` reporta sucesso com o processo possivelmente morto.**
3. **Gate `UNAVAILABLE` não distingue "não rodou" de "não se aplica".** Os dois
   aparecem igual no desfecho, e `UNAVAILABLE` contamina o status geral.
4. **Nada compara o documento de cena com o `.skp` no disco.** Não há detecção de
   divergência: a cena pode acumular 40 edições enquanto o `.skp` é de agosto, e
   nenhuma tela diz isso.
5. **O trace registra intenção e resultado, não evidência.** `tool.invoke` grava
   o que a tool devolveu. Não há campo para "como isto foi verificado".

---

## O que está sólido (e não deve ser mexido)

Registro explícito para o Codex não "consertar" o que está certo:

- **Governança do registry.** Tool fora da tabela não vira comando; argumento
  desconhecido é recusado; risco HIGH pede confirmação. Testado.
- **Um envelope de trace só**, compartilhado com o Inspector, com parentesco de
  span. `AgentTraceContractTest` trava a costura de ponta a ponta.
- **Gate automático após alteração geométrica** — não é escolha do modelo.
- **Desfecho vem do gate, não do texto do modelo.** `GATE_FAILED` sobrevive a um
  modelo dizendo "ficou ótimo".
- **Undo exato** por log de edições invertíveis, com o documento reconstruível.
- **Sem watchdog.** `startAll`/`restartFailed` só por ação humana, com teste
  travando a ausência de ressurreição automática.
- **Degradação parcial honesta.** Ollama fora não derruba o caminho
  determinístico; host fora aparece com o motivo real.
