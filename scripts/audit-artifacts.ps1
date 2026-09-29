$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$lockData = Get-Content -Raw "$projectRoot/models/models.lock.json" | ConvertFrom-Json
$rows = @($lockData.artifacts | ForEach-Object {
    $path = Join-Path $projectRoot $_.path
    $item = Get-Item -LiteralPath $path
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $matches = $actual -eq $_.sha256 -and $item.Length -eq $_.bytes
    if (!$matches) { throw "Artifact mismatch: $path" }
    [ordered]@{id=$_.id; path=$_.path; bytes=$item.Length; sha256=$actual; matchesLock=$matches; validationStatus=$_.status}
})
$report = [ordered]@{measuredAtUtc=[DateTime]::UtcNow.ToString('o'); kind='host_artifact_integrity_only'; artifacts=$rows; androidInferenceTested=$false}
New-Item -ItemType Directory -Force "$projectRoot/benchmark" | Out-Null
$report | ConvertTo-Json -Depth 8 | Out-File -LiteralPath "$projectRoot/benchmark/artifact-audit.json" -Encoding utf8
$rows | ForEach-Object { [PSCustomObject]$_ } | Format-Table id,bytes,matchesLock -AutoSize
