$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$lockData = Get-Content -Raw "$projectRoot/models/models.lock.json" | ConvertFrom-Json
$selected = @($lockData.artifacts | Where-Object { $_.path.StartsWith('models/source/asr/en-2023-06-21/') })
if ($selected.Count -ne 4) { throw 'Expected exactly four pinned English artifacts' }
foreach ($artifact in $selected) {
    $destination = Join-Path $projectRoot $artifact.path
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    if (!(Test-Path -LiteralPath $destination)) {
        & curl.exe --fail --location --silent --show-error --retry 2 --connect-timeout 30 --max-time 900 --output $destination $artifact.source
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $destination; preserve/check any partial file before retry" }
    }
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Get-Item -LiteralPath $destination).Length -ne $artifact.bytes -or $hash -ne $artifact.sha256) {
        throw "Pinned checksum mismatch: $destination (existing file preserved)"
    }
    Write-Output "Verified $($artifact.id)"
}
