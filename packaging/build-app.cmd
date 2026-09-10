@echo off
REM Gera o app clicavel (app-image do jpackage) numa pasta app\rN NOVA.
REM
REM Por que rotativo: reutilizar um caminho que ja teve uma app-image APAGADA produz
REM uma imagem que nao inicia — o launcher sobe com ~16 MB, o JVM nunca comeca e nao
REM ha mensagem de erro. Testado: caminho virgem funciona; o MESMO caminho depois de
REM rmdir falha; mover uma imagem boa para dentro dele tambem falha. Nao e Defender
REM (sem deteccao registrada). Detalhe em docs\field-notes-jpackage.md.
REM
REM ASCII de proposito: .cmd/.ps1 acentuado quebra o parser do PowerShell 5.1.
setlocal

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
set "ROOT=%~dp0.."
cd /d "%ROOT%"

echo [1/4] build da UI (Vite -^> src\main\resources\web)
pushd ui
call npm run build || goto :fail
popd

echo [2/4] build do Java (testes incluidos)
call mvnw.cmd -B package || goto :fail

echo [3/4] fechando o app e escolhendo uma pasta NOVA
taskkill /F /IM HarnessApp.exe >nul 2>&1
timeout /t 2 /nobreak >nul

set "SLOT="
for /l %%i in (2,1,99) do (
  if not defined SLOT if not exist "app\r%%i" set "SLOT=r%%i"
)
if not defined SLOT (
  echo Nao achei slot livre em app\r2..r99. Apague os antigos.
  exit /b 1
)
echo     usando app\%SLOT%

echo [4/4] jpackage
"%JAVA_HOME%\bin\jpackage.exe" ^
  --type app-image ^
  --name HarnessApp ^
  --app-version 0.1.0 ^
  --dest "app\%SLOT%" ^
  --input target\dist ^
  --main-jar harness-app-0.1.0-SNAPSHOT.jar ^
  --main-class inspector.ui.Launcher ^
  --icon packaging\harness-app.ico ^
  --vendor "Felipe Modesto" ^
  --description "AI Pipeline Inspector" ^
  --java-options "--enable-native-access=ALL-UNNAMED" ^
  --java-options "-DtraceDir=E:\Claude\apps\sketchup-mcp\.ai_bridge\traces" ^
  --java-options "-DconsultsDir=E:\Claude\apps\sketchup-mcp\.ai_bridge\responses" || goto :fail

echo.
echo OK: app\%SLOT%\HarnessApp\HarnessApp.exe
echo Repontando o atalho da area de trabalho...
powershell -NoProfile -File "packaging\make-shortcut.ps1" || goto :fail
echo.
echo Pastas antigas em app\ podem ser apagadas a mao quando quiser.
exit /b 0

:fail
echo.
echo FALHOU (exit %errorlevel%)
exit /b %errorlevel%
