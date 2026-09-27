# ADR-002 — O control plane tem taxonomia de eventos PRÓPRIA

**Data:** 2026-09-27 · **Status:** aceito · **Contexto:** envelope v1

## O problema, e o erro de raciocínio que o revelou

Ao acrescentar o gate de resolução de alvo, emiti dois nomes novos
(`target.guard.allowed`, `target.guard.blocked`). Verifiquei que o lado Java não
valida nome de evento e conclui que **por isso** era seguro acrescentar.

Isso está errado, e a review apontou com razão: **a ausência de um mecanismo de
validação não é autorização.** É exatamente assim que um catálogo declarado
FECHADO deixa de ser fechado sem ninguém perceber.

## O que a investigação mostrou

O pipeline (`sketchup-mcp/core/observability/events.py`) tem `EVENT_NAMES` com
**44 nomes**, validado lá dentro. Conferindo o que o Harness emite contra esse
conjunto:

| Nome emitido pelo agente | Está nos 44? |
|---|---|
| `run.started` | ✅ |
| `run.finished` | ✅ |
| `agent.plan` | ❌ |
| `capability.lookup` | ❌ |
| `tool.invoke` | ❌ (o catálogo tem `tool.started`/`tool.finished`/`tool.failed`) |
| `tool.rejected` | ❌ |
| `tool.verified` | ❌ |
| `gate.run` | ❌ (o catálogo tem `gate.started`/`gate.passed`/`gate.failed`) |

**A divergência não começou com o `target.*`.** Ela existe desde o slice 1 e
nunca foi declarada em lugar nenhum. O `target.*` foi a instância mais recente de
um drift que já estava instalado.

## Decisão

**São duas taxonomias, e isso agora é explícito.**

Um evento do pipeline descreve uma **run de geração** do `.skp`. Um evento do
Harness descreve um **comando do Felipe** — planejamento, admissão, resolução de
alvo, verificação. São planos diferentes com ciclos de vida diferentes; forçar um
vocabulário só esconderia a diferença em vez de expressá-la.

O conjunto do control plane vive em `harness.agent.domain.HarnessEvents.CLOSED`,
é **fechado e validado** (`AgentTrace.emit` chama `HarnessEvents.require`), e
`HarnessEventsTest` trava a lista nas duas direções: emitir nome não declarado
falha, e acrescentar nome sem tocar no teste também falha.

Acrescentar nome passa a ser **ato deliberado**, com registro aqui.

## Divergência conhecida, NÃO reconciliada

O agente usa `tool.invoke` (um evento que já carrega o resultado) onde o pipeline
usa o par `tool.started`/`tool.finished`; e `gate.run` onde o pipeline usa
`gate.started`/`gate.passed`/`gate.failed`.

Unificar é possível e talvez desejável — o Inspector lê os dois. Mas mexeria na
projeção (`inspector.projection`) e no agrupamento por span, que hoje têm teste
travando. **Fica como questão aberta, não como dívida escondida.**

Critério para decidir no futuro: se o Inspector passar a comparar run de pipeline
com comando de agente na mesma tela, unificar paga. Enquanto as duas visões forem
separadas, a divergência é informação, não defeito.

## Consequência prática

Quem for acrescentar um evento no control plane:

1. Declare a constante em `HarnessEvents`.
2. Inclua no `CLOSED`.
3. Atualize `HarnessEventsTest.oConjuntoFechadoEexatamenteEste`.
4. Registre aqui o motivo.

Se você está tentado a pular os passos porque "não tem validador" — tem, desde
este ADR.
