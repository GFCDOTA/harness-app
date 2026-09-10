# Field note — jpackage app-image no Windows

Colhido em 2026-09-10, montando o app clicavel do Harness App.

## 1. Classe `main` que estende `Application` bloqueia o classpath

O launcher do JavaFX recusa iniciar quando a classe `main` estende
`javafx.application.Application` e os modulos estao no **classpath** em vez do
module-path — aborta com *"JavaFX runtime components are missing"*.

Uma classe intermediaria que **nao** estende `Application` contorna a checagem:

```java
public final class Launcher {
    public static void main(String[] args) { InspectorApp.main(args); }
}
```

E o que permite `jpackage --input <dir com todos os jars>` funcionar **sem jlink e
sem module-path**. O custo e um aviso benigno no startup:
`Unsupported JavaFX configuration: classes were loaded from 'unnamed module'`.

## 2. NUNCA troque um arquivo dentro de uma app-image ja gerada

Copiar um jar novo por cima do antigo dentro de `dist/<App>/app/` produz um app que
**morre no startup**, antes de qualquer saida, com:

```
GetMessage() failed. System error [1400] (identificador da janela e invalido)
```

Diagnostico que confirma: o MESMO jar rodando por `mvn javafx:run` funciona, e uma
app-image **recem-gerada** a partir do mesmo `target/dist` tambem funciona. Só a
imagem com troca a quente quebra. **Regra: app-image e imutavel — rebuild, nunca
patch.**

Agrava o diagnostico: cada tentativa falha deixa um processo `.exe` pendurado
segurando handles de janela, e as execucoes seguintes falham com o mesmo 1400 por
motivo diferente. Matar os zumbis antes de concluir qualquer coisa.

## 3. O runtime embutido nao tem `java.exe`

`jpackage` remove os launchers do runtime que empacota. Nao da para testar a imagem
chamando `dist/<App>/runtime/bin/java.exe` — ele nao existe. Para smoke check
**gere uma imagem separada com `--win-console`** e as opcoes de teste embutidas via
`--java-options`; a imagem final fica sem console.

## 4. `--java-options` e onde config de maquina deve morar

Caminho absoluto desta maquina (o diretorio de traces) entra por
`--java-options "-DtraceDir=..."` no empacotamento, nao no codigo. O codigo continua
generico e testavel; a imagem carrega o que e especifico da maquina.

## 5. Apagar a app-image com o app ABERTO envenena o diretorio

Sintoma: apos `rmdir` + rebuild no MESMO caminho, o launcher sobe como um processo
de ~16 MB, sem janela, e o JVM nunca inicia. Nenhuma mensagem util.

O diagnostico so fecha por eliminacao, e vale registrar a sequencia porque cada
hipotese obvia estava errada:

| Hipotese | Teste | Resultado |
|---|---|---|
| falta modulo no runtime (`java.net.http`, `java.desktop`) | ler `runtime/release` | ambos PRESENTES — hipotese morta |
| o `--icon` gerado por PIL quebra o launcher | build com icone em outro caminho | **subiu** — icone inocente |
| `-DconsultsDir` com barras invertidas | build com a opcao em outro caminho | **subiu** — opcao inocente |
| o NOME "HarnessApp" | mesmo nome, caminho diferente | **subiu** — nome inocente |
| o CAMINHO | rebuild em `app/` em vez de `dist/` | **subiu** |

Conclusao: o diretorio fica num estado de pending-delete do Windows quando e apagado
com o `.exe` ainda em execucao, e a imagem nova escrita ali nasce quebrada. O
`build-app.cmd` passou a fazer `taskkill /F /IM HarnessApp.exe` ANTES de apagar.

Corolario do item 2: nao basta "rebuild, nunca patch" — e **feche o app antes do
rebuild**, e prefira um caminho que nunca foi apagado sob uso.

## 6. Correcao do item 5: o caminho fica queimado APOS QUALQUER delecao

O item 5 culpava "apagar com o app aberto". Testes seguintes mostraram que a
condicao e mais ampla e mais chata:

| Cenario | Resultado |
|---|---|
| jpackage num caminho VIRGEM | **funciona** |
| mesmo caminho, apos `rmdir` (app ja fechado) | falha |
| imagem BOA movida para dentro do caminho apagado | falha |
| Defender como causa | descartado — `Get-MpThreatDetection` sem registro |

Sintoma sempre igual: launcher sobe com 8-16 MB, o processo do JVM nunca aparece,
nenhuma mensagem. Uma imagem sadia mostra DOIS processos, o segundo com ~290 MB —
**e essa a checagem que vale**, nao "o processo existe".

**Receita adotada:** o build nunca reutiliza caminho. `build-app.cmd` procura o
primeiro `app\rN` livre, gera ali, e `make-shortcut.ps1` aponta o atalho para o `rN`
mais recente. Pastas antigas podem ser apagadas a mao depois — o que nao pode e
gravar de novo por cima de uma que ja foi apagada.
