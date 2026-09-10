# Harness App — AI Pipeline Inspector

Janela nativa que mostra **o que o pipeline de IA/RAG realmente fez** numa run:
o que foi RAG, o que foi LLM, o que foi harness, o que foi código determinístico —
e quais passos bateram numa **API externa** contra os que rodaram **local**.

Consome os traces emitidos pelo `core/observability` do `sketchup-mcp`. É **cliente**
do trace; não é um segundo backend.

## Abrir

Clique em **Harness App** na área de trabalho. Ele abre no trace mais recente.

Durante desenvolvimento:

```bash
JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot" ./mvnw javafx:run
```

Regerar o app clicável (UI + Java + jpackage + atalho):

```bash
packaging\build-app.cmd
powershell -File packaging\make-shortcut.ps1
```

## As três visões

**Pipeline View** (principal) — o caixograma. Cada passo é um nó com um **boneco** que
diz o papel (bibliotecário, tradutor de vetores, banco vetorial, modelo de linguagem,
arquiteto, fiscal, mecânico), o status, a duração, e um chip `HTTP externo` / `local`.
A aresta de **fallback** sai tracejada e âmbar, porque desvio precisa parecer desvio.
Clique num nó → painel lateral com os eventos crus daquele passo.

**Events View** (secundária) — a lista dos eventos, um a um. É debugger, não a
experiência principal.

O app abre em **tema escuro**; o botão no canto superior direito alterna e a escolha
fica gravada. A escolha é explícita porque o WebView do JavaFX não acompanha o tema
do Windows de forma confiável.

**Oráculo** — a esteira está viva? (GPT-Docker `:8899`, Qdrant `:6333`, Ollama
`:11434`, sondados a cada 5 s) e o histórico das consultas ao GPT, com a **prévia
dos dois lados**: o que eu perguntei e o que ele respondeu, lado a lado, com filtro
por título ou conteúdo.

A lista ordena pela **data de escrita do arquivo**, não pelo nome: os registros novos
começam com letra (`GPT_`, `SPIKE_`) e os antigos com dígito, e ordenar por nome
colocava um registro de ontem acima de um de hoje.

### Ligar serviço pelo app

Serviço fora do ar ganha um botão **ligar**. Ele dispara **uma vez, por clique seu**.

Essa é a linha que preserva a lição do NOC: o que custou caro foi ressurreição
*automática* — watchdog, Scheduled Task, respawn em loop, PowerShell que o Defender
flagava. Um botão que uma pessoa aperta é outra coisa. Este app **não** reinicia
nada sozinho, **não** tenta de novo e **não** reage a "caiu".

Os comandos são uma **lista fechada declarada no código** (`InspectorApp.ACTIONS`).
A página só manda um id conhecido, nunca um comando — sem isso um painel web viraria
um shell. E a ponte continua de uma direção só: a UI **enfileira** o pedido e o Java
**puxa** (`WebBridge.drainRequests`), então nenhum objeto Java é exposto ao JS e o
`JSObject` proibido segue fora.

### Como 27 eventos viram 8 caixas

A regra é derivada dos dados, sem apelido inventado:

1. se o evento traz `meta.harnessKind`, essa é a chave do passo — é o próprio lado
   Python declarando "isto é uma camada de harness", e é o que colapsa os 10 eventos
   do correction loop num passo só;
2. senão, a chave é `category` + **família do component** (trecho antes do primeiro
   ponto: `gate.opening_host` → `gate`). É o que funde os 3 gates numa caixa e separa
   embedding de banco vetorial.

Corridas consecutivas de mesma chave viram um passo. Voltar a uma chave anterior abre
passo novo — porque o pipeline realmente passou por ali de novo. `run.started` e
`run.finished` não são passos: são os terminais, e a UI desenha início e fim.

## Desenho

```
TraceSource → domain (Run/Span/TraceEvent/Pipeline) → TraceProjection → JSON → React
```

Uma direção só. As travas que sustentam isso:

| Trava | Por quê |
|---|---|
| Domínio sem Jackson e sem JavaFX | records puros; quem lê JSON é o adapter, quem desenha é a UI |
| Bridge **sem** `netscape.javascript.JSObject` | está *deprecated e marcado para remoção*; o Java só chama `window.inspector.loadRun(json)` via `executeScript`, e **nenhum objeto Java é exposto ao JS** |
| Assets **empacotados**, zero CDN | app de observabilidade não pode morrer porque a internet caiu — falharia exatamente quando é mais necessário. O Vite gera bundle estático em `src/main/resources/web/`, versionado, e o `./mvnw javafx:run` funciona sem npm |
| `category` é `String`, não enum | o catálogo é fechado no lado Python, dono da taxonomia; um enum aqui viraria exceção quando aparecesse categoria nova, e o Inspector existe para observar, não para recusar |
| Medida ausente é `null`, nunca `0.0` | zero faria a UI desenhar "instantâneo" onde não houve medição |
| `started` não é desfecho | se competisse na severidade, um passo que abriu **e terminou** apareceria como "started" para sempre. Sem desfecho + com abertura = `running` |
| `Launcher` não estende `Application` | é o que permite JavaFX no classpath e o `jpackage` sem jlink |

`SseTraceSource` existe e **falha alto**: é a costura do tempo real, e o teste que
prova a exceção impede a fase seguinte de ser esquecida em silêncio. Quando o SSE
entrar, muda **só o adapter** — domínio e UI já falam `TraceEvent`.

## O que ele não é

Não é servidor e não é supervisor: **observa**. Não reinicia nada, não cria watchdog,
não registra Scheduled Task, não chama PowerShell para ressuscitar processo. Serviço
fora do ar aparece como fora do ar. Fechar a janela mata o processo — não existe
`System.exit` no código, quem encerra é o toolkit.

## Onde ele acha os traces

Precedência: `-Dtrace=<arquivo>` → `-DtraceDir=<dir>` → `INSPECTOR_TRACE_DIR` →
`traces-local/` ao lado do app. Sem nenhuma fonte ele **falha ensinando as opções**,
em vez de abrir vazio fingindo normalidade.

O app empacotado carrega o diretório de traces do `sketchup-mcp` embutido como
`-DtraceDir` (ver `packaging/build-app.cmd`). É o único ponto onde um caminho desta
máquina aparece — de propósito: config de máquina vive na camada de empacotamento,
não no código.

## Estado

Pipeline View (horizontal, numerada) + deep dive + Events View + Oráculo com botão
de ligar — **feito**. 74 testes verdes, nenhum importa JavaFX.

O app também **se fotografa**: `-DsnapshotDir=<dir>` grava PNG de cada visão durante
o smoke check. Existe porque a janela nativa não é capturável de fora neste ambiente,
e sem imagem não dá para pedir revisão visual a ninguém.

Evidência do smoke check (`./mvnw javafx:run -Dselftest=true`) contra o trace real de
27 eventos:

```
pipeline: 8 passos · 9 arestas · 1 fallback · 7 personas · 3 nós HTTP externos · errors=[]
detalhe : clique no nó abre o painel (detailOpenFor=s1)
events  : 27 linhas
```

Não implementado ainda, por ordem: **SSE + `Last-Event-ID`** → geometry observability
→ learning mode → replay/scrubber. (O health HTTP saiu de ordem: entrou junto com o
painel do Oráculo, a pedido.)

Regra-mãe herdada e intacta: *observability describes execution; it never changes execution.*
