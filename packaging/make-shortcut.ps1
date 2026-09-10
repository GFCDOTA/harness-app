# Cria/atualiza o atalho "Harness App" na area de trabalho.
# ASCII de proposito (gotcha do parser do PS 5.1 com acento sem BOM).
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$exe  = Join-Path $root 'dist\HarnessApp\HarnessApp.exe'
$ico  = Join-Path $root 'packaging\harness-app.ico'
if (-not (Test-Path $exe)) { throw "exe nao encontrado. Rode packaging\build-app.cmd antes: $exe" }

$lnk = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Harness App.lnk'
$ws  = New-Object -ComObject WScript.Shell
$sc  = $ws.CreateShortcut($lnk)
$sc.TargetPath       = $exe
$sc.WorkingDirectory = Split-Path $exe -Parent
$sc.IconLocation     = "$ico,0"
$sc.Description      = 'AI Pipeline Inspector - ve o que o pipeline de IA/RAG esta fazendo'
$sc.Save()
Write-Output "atalho criado: $lnk"
