<#
Downloads, exports and stages every neural voice source, one language at a time.

Each language: fetch the AI4Bharat checkpoint archive, keep only the HiFi-GAN
generator, export it to float32 ONNX, copy EchoBharat's int8 FastPitch and token
table alongside, verify by synthesising one sentence, then delete the checkpoint.
Peak disk stays near 2.5 GB rather than 13 GB.

  powershell -File scripts/build-all-tts-vocoders.ps1
#>
[CmdletBinding()]
param(
    [string[]]$Languages = @('en', 'bn', 'gu', 'kn', 'ml', 'mr', 'ta', 'te'),
    [string]$Root = 'D:\SIH'
)

$ErrorActionPreference = 'Continue'
$python = Join-Path $Root '.tools\odia-export\Scripts\python.exe'
$env:PYTHONIOENCODING = 'utf-8'

foreach ($language in $Languages) {
    $staged = Join-Path $Root "models\source\tts\indictts\$language\vocoder.onnx"
    if (Test-Path $staged) {
        Write-Host "== $language already exported"
        continue
    }

    Write-Host "== $language : fetching checkpoint"
    & powershell -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $Root 'scripts\fetch-indictts-checkpoint.ps1') `
        -Language $language -Root $Root
    if ($LASTEXITCODE -ne 0) { Write-Warning "$language fetch failed"; continue }

    Write-Host "== $language : exporting float32 vocoder"
    & $python (Join-Path $Root 'scripts\export-tts-vocoder.py') $language
    if (-not (Test-Path $staged)) { Write-Warning "$language export failed"; continue }

    Write-Host "== $language : staging and verifying the pair"
    & $python (Join-Path $Root 'scripts\stage-tts-source.py') $language

    # The 969 MB training checkpoint is no longer needed once ONNX exists.
    $raw = Join-Path $Root ".tools\indictts-vocoders\raw\$language"
    if (Test-Path $raw) { Remove-Item $raw -Recurse -Force }
    Write-Host "== $language done`n"
}

Write-Host 'all requested languages processed'
Get-ChildItem (Join-Path $Root 'models\source\tts\indictts') -Directory |
    ForEach-Object {
        $voc = Join-Path $_.FullName 'vocoder.onnx'
        $acoustic = Join-Path $_.FullName 'acoustic.onnx'
        [PSCustomObject]@{
            Language = $_.Name
            VocoderMiB = if (Test-Path $voc) { [math]::Round((Get-Item $voc).Length / 1MB, 2) } else { $null }
            AcousticMiB = if (Test-Path $acoustic) { [math]::Round((Get-Item $acoustic).Length / 1MB, 2) } else { $null }
        }
    } | Format-Table -AutoSize
