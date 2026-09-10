# Cria/atualiza o atalho "Harness App" na area de trabalho.
# ASCII de proposito (gotcha do parser do PS 5.1 com acento sem BOM).
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$ico  = Join-Path $root 'packaging\harness-app.ico'

# O build usa pasta NOVA a cada vez (app\rN) porque reutilizar um caminho ja apagado
# produz imagem que nao inicia. Entao o atalho aponta para a pasta rN mais recente,
# e nao para um caminho fixo.
$appRoot = Join-Path $root 'app'
$slot = Get-ChildItem -LiteralPath $appRoot -Directory -Filter 'r*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'HarnessApp\HarnessApp.exe') } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
if (-not $slot) { throw "nenhuma imagem em $appRoot\r*. Rode packaging\build-app.cmd antes." }
$exe = Join-Path $slot.FullName 'HarnessApp\HarnessApp.exe'

$lnk = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Harness App.lnk'
$ws  = New-Object -ComObject WScript.Shell
$sc  = $ws.CreateShortcut($lnk)
$sc.TargetPath       = $exe
$sc.WorkingDirectory = Split-Path $exe -Parent
$sc.IconLocation     = "$ico,0"
$sc.Description      = 'AI Pipeline Inspector - ve o que o pipeline de IA/RAG esta fazendo'
$sc.Save()
Write-Output "atalho criado: $lnk"
