@echo off
REM Gera o app clicavel (app-image do jpackage) em dist\HarnessApp.
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

echo [3/4] limpando imagem anterior
if exist "dist\HarnessApp" rmdir /s /q "dist\HarnessApp"

echo [4/4] jpackage
"%JAVA_HOME%\bin\jpackage.exe" ^
  --type app-image ^
  --name HarnessApp ^
  --app-version 0.1.0 ^
  --dest dist ^
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
echo OK: dist\HarnessApp\HarnessApp.exe
echo Atalho: powershell -File packaging\make-shortcut.ps1
exit /b 0

:fail
echo.
echo FALHOU (exit %errorlevel%)
exit /b %errorlevel%
