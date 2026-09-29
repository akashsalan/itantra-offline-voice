<#
Creates an isolated environment for exporting AI4Bharat FastPitch acoustic models.

Reuses the existing torch installation via --system-site-packages so this does not
download another multi-gigabyte torch. coqui-tts is needed because the checkpoints
are coqui ForwardTTS/FastPitch models: instantiating the real class is safer than
reimplementing the encoder, duration predictor and pitch predictor by hand.

  powershell -File scripts/setup-tts-export-env.ps1
#>
[CmdletBinding()]
param([string]$Root = 'D:\SIH')

$ErrorActionPreference = 'Stop'
$base = Join-Path $Root '.tools\odia-export\Scripts\python.exe'
$venv = Join-Path $Root '.tools\tts-export'
$python = Join-Path $venv 'Scripts\python.exe'

if (-not (Test-Path $python)) {
    Write-Host 'creating .tools/tts-export (reusing system torch)'
    & $base -m venv --system-site-packages $venv
}

& $python -m pip install --quiet --upgrade pip
& $python -m pip install 'coqui-tts==0.26.2'
if ($LASTEXITCODE -ne 0) { throw "coqui-tts install failed (exit $LASTEXITCODE)" }

& $python -c "import TTS, torch, onnxruntime; print('TTS', TTS.__version__); print('torch', torch.__version__); print('ort', onnxruntime.__version__)"
