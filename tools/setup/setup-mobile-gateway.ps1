$ErrorActionPreference = 'Stop'
$capriMobileRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'python-runtime.ps1')
$capriMobileRuntime = Get-CapriPythonRuntime -ProjectRoot $capriMobileRoot
$capriMobilePython = Ensure-CapriVirtualEnvironment -ProjectRoot $capriMobileRoot -Name 'mobile-gateway-venv' -Runtime $capriMobileRuntime
$capriMobileRequirements = Join-Path $capriMobileRoot 'integrations/mobile_gateway/requirements.txt'
& $capriMobileRuntime.Python (Join-Path $PSScriptRoot 'uv-retry.py') --uv $capriMobileRuntime.Uv -- pip install --python $capriMobilePython -r $capriMobileRequirements
if ($LASTEXITCODE -ne 0) { throw 'Could not install Agent Remote dependencies.' }
