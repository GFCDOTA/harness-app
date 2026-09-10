# inspector-desktop — host desktop do AI Pipeline Inspector

Janela nativa que mostra, caixa por caixa, o que o pipeline de IA/RAG fez numa run:
o que foi RAG, o que foi LLM, o que foi harness, o que foi código determinístico —
e quais passos bateram numa **API externa** contra os que rodaram **local**.

Decisão de arquitetura: **ADR-001** em `docs/specs/AI_PIPELINE_INSPECTOR.md` §7.1.

## Rodar

```bash
JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot" ./mvnw javafx:run
```

Sem argumento ele abre o `.jsonl` **mais recente** de `../.ai_bridge/traces/`.
Para um trace específico: `./mvnw javafx:run -Dtrace=../.ai_bridge/traces/<arquivo>.jsonl`

Testes (não precisam de JavaFX nem de tela):

```bash
JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot" ./mvnw test
```

Smoke check não-interativo (abre, mede o DOM, imprime e fecha):

```bash
JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot" ./mvnw javafx:run -Dselftest=true
```

## O que ele é, e o que não é

- **É** um cliente do trace. **Não é** um servidor — não existe segundo backend HTTP.
- **Observa.** Não reinicia nada, não cria watchdog, não registra Scheduled Task, não
  chama PowerShell. Serviço fora do ar aparece como fora do ar. Fechar a janela mata o
  processo (não existe `System.exit` no código; quem encerra é o toolkit).

## Desenho

```
TraceSource → domain (Run/Span/TraceEvent) → TraceProjection → JSON → React
```

Uma direção só. E as travas que sustentam isso:

| Trava | Por quê |
|---|---|
| Domínio sem Jackson e sem JavaFX | `TraceEvent`/`Run`/`Span` são records puros; quem lê JSON é o adapter, quem desenha é a UI |
| Bridge **sem** `netscape.javascript.JSObject` | está *deprecated e marcado para remoção*; o Java só chama `window.inspector.loadRun(json)` / `appendEvent(json)` via `executeScript`, e **nenhum objeto Java é exposto ao JS** |
| React **vendorizado** (`web/vendor/`) | app de observabilidade não pode morrer porque a CDN caiu — falharia exatamente quando é mais necessário |
| Sem Babel | `createElement` dispensa transpilação em runtime (139 KB em vez de 2,4 MB) e o mesmo arquivo serve a superfície browser opcional |
| `category` é `String`, não enum | o catálogo é fechado **no lado Python**, que é o dono da taxonomia; um enum aqui viraria exceção quando aparecesse categoria nova, e o Inspector existe para observar, não para recusar |
| Medida ausente é `null`, nunca `0.0` | `0.0` faria a UI desenhar "instantâneo" onde não houve medição |

`SseTraceSource` existe e **falha alto**: é a costura do tempo real, e o teste que
prova a exceção é o que impede a fase seguinte de ser esquecida em silêncio. Quando o
SSE entrar, muda **só o adapter** — domínio e UI já falam `TraceEvent`.

## Estado

Vertical slice 1 (replay do JSONL) — **feito**. 35 testes verdes.
Evidência do smoke check contra o trace real de 27 eventos:

```
boxCount=27  categories={OBSERVABILITY:2, RAG:9, HARNESS:11, LLM:2, DETERMINISTIC:3}
externalCalls=6  distinctBoxColors=5  listScrollH=1975 vs listClientH=697  errors=[]
```

Não implementado ainda, por ordem: SSE + `Last-Event-ID` → health HTTP →
geometry observability → learning mode → replay/scrubber.

Regra-mãe herdada e intacta: *observability describes execution; it never changes execution.*
