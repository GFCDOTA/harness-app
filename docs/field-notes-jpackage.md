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
