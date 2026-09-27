# ADR-003 — Resolver alvo pelo LABEL da cena é estratégia SEGURA e TRANSITÓRIA

**Data:** 2026-09-27 · **Status:** aceito, com prazo de validade declarado

## Contexto

O Target Resolution Gate existe porque `pinta o armário da COZINHA` pintava o
`sala_de_jantar_sala_de_estar.sofa` e o desfecho saía `CLEAN`. O gate resolve isso
consultando a cena real em vez de confiar no objeto que o modelo escolheu.

Hoje o vocabulário de identificação sai de onde já existe verdade:

- **Cômodo** por token **distintivo** do nome (`COZINHA` → "cozinha" identifica;
  "suite" não identifica, porque está em `SUITE 01` e `SUITE 02`).
- **Objeto** pelo `label` e pelos `kinds` que o pipeline já emite. Existe
  `Armario de servico` na A.S., então "armário" casa com **ele**, não com um sofá.

## Por que isso é bom AGORA

**Falha fechado e não inventa.** Não há tabela de sinônimos escrita à mão, então
não há palpite: se o nome não aparece na cena, o resolver não afirma nada e o
comando vago simplesmente não é restringido. Nenhum dado novo precisou ser
produzido, e nenhuma capability foi criada — `list_rooms` e `list_objects` já
existiam.

## Por que NÃO é o modelo final

Isto é casamento **textual**, não semântico. As limitações são reais e conhecidas:

- `armário` não casa com `upper_cabinet` — o label da cozinha é em inglês. Hoje o
  gate ainda protege (bloqueia por `ROOM_MISMATCH`), mas não **entende** o pedido.
- Sinônimo legítimo do português (`guarda-roupa` / `armário`, `poltrona` /
  `cadeira`) não é reconhecido.
- Label é texto de APRESENTAÇÃO. Usá-lo como chave de identificação acopla a
  resolução a uma string que pode mudar por motivo de UI.

## O modelo certo, quando houver

A cena deve declarar a semântica explicitamente, e o resolver consultar isso:

```
id:      cozinha.upper_cabinet_01
room:    cozinha
kind:    cabinet
subkind: upper_cabinet
label:   Armário superior 01      <- apresentação, não chave
```

Com `room`/`kind`/`subkind` explícitos, `ROOM_MISMATCH` e `KIND_MISMATCH` passam a
ser comparação de campo em vez de interseção de tokens, e uma tabela de sinônimos
PT→`kind` passa a ser possível **sem** virar adivinhação (ela mapearia para um
vocabulário fechado, não para texto livre).

Isso é trabalho no `sketchup-mcp` (quem produz os boxes), não no Harness.

## Decisão

Aceitar o casamento por label **como estratégia de segurança transitória**, com
esta placa: **não deixar heurística textual virar arquitetura permanente.**

Quando o pipeline expuser `kind`/`subkind` canônicos, o
`TargetResolver` migra para eles e o label volta a ser só apresentação. Os testes
de `TargetResolverTest` são escritos sobre o COMPORTAMENTO (bloqueia cross-room,
bloqueia cross-kind, pergunta no ambíguo, não trava comando vago), então a
migração não deveria reescrevê-los — e se reescrever, é sinal de que o
comportamento mudou e merece revisão.

## O que NÃO fazer enquanto isso

Não acrescentar tabela de sinônimos PT→label agora. Meio-feita, ela produz falso
positivo (bloqueia pedido legítimo) ou falso negativo (deixa passar alvo errado),
e as duas destroem a confiança que o gate acabou de comprar.
