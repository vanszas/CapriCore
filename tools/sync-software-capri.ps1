$ErrorActionPreference = 'Stop'
$src = Split-Path -Parent $PSScriptRoot
$dst = Join-Path $src 'Software\Capri'

if (-not (Test-Path $dst)) {
    throw "Installed Capri directory not found at $dst"
}

New-Item -ItemType Directory -Force -Path (Join-Path $dst 'integrations\mobile_gateway') | Out-Null
Copy-Item "$src\integrations\mobile_gateway\*" "$dst\integrations\mobile_gateway\" -Recurse -Force

$files = @(
    'apps\desktop\main.mjs',
    'apps\desktop\chat-service.mjs',
    'apps\desktop\preload.cjs',
    'apps\desktop\main\agent-remote-server.mjs',
    'apps\desktop\main\settings-channels.mjs',
    'apps\desktop\renderer\app.js',
    'apps\desktop\renderer\cyberpunk.css',
    'apps\desktop\renderer\index.html',
    'apps\desktop\renderer\settings-app.mjs',
    'apps\desktop\renderer\settings-registry.mjs',
    'apps\desktop\renderer\settings.html',
    'apps\desktop\renderer\skill-toolbar.mjs',
    'packages\runtime\src\client.mjs',
    'packages\runtime\src\config\settings.mjs',
    'packages\runtime\src\index.mjs',
    'packages\runtime\src\providers\chat-router.mjs',
    'packages\skills\src\settings-catalog.mjs'
)

foreach ($rel in $files) {
    $from = Join-Path $src $rel
    $to = Join-Path $dst $rel
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $to) | Out-Null
    Copy-Item $from $to -Force
}

Write-Output "Synced v1.2.0 + custom Agent Remote, Hermes provider, QR fix, and sequential startup to $dst"
