$ErrorActionPreference = 'Stop'
$src = Split-Path -Parent $PSScriptRoot
$dst = Join-Path $src 'Software\Capri'

if (-not (Test-Path $dst)) {
    throw "Installed Capri directory not found at $dst"
}

New-Item -ItemType Directory -Force -Path (Join-Path $dst 'integrations\mobile_gateway') | Out-Null
Copy-Item "$src\integrations\mobile_gateway\*" "$dst\integrations\mobile_gateway\" -Recurse -Force
Copy-Item "$src\apps\desktop\main\agent-remote-server.mjs" "$dst\apps\desktop\main\agent-remote-server.mjs" -Force

# Patch main.mjs safely for sequential startup without overwriting newer installer code
$mainPath = Join-Path $dst 'apps\desktop\main.mjs'
$mainText = Get-Content $mainPath -Raw -Encoding UTF8
$patchedMain = $mainText -replace 'sequential:\s*responseTiming\.sequentialModels', 'sequential: true'
if ($patchedMain -ne $mainText) {
    Set-Content -Path $mainPath -Value $patchedMain -Encoding UTF8 -NoNewline
}

# Sync renderer/app.js Blob QR fix
$appPath = Join-Path $dst 'apps\desktop\renderer\app.js'
$appText = Get-Content $appPath -Raw -Encoding UTF8
if ($appText -notmatch 'agentRemoteQrUrl') {
    Copy-Item "$src\apps\desktop\renderer\app.js" $appPath -Force
}

Write-Output "Synced mobile_gateway, agent-remote-server.mjs, QR fix, and sequential startup to $dst"
