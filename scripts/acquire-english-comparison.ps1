param([string]$ParakeetDirectory = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$lockData = Get-Content -Raw "$projectRoot/models/models.lock.json" | ConvertFrom-Json
if (!$ParakeetDirectory) {
    $ParakeetDirectory = Join-Path $projectRoot 'downloads/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8'
}
$selected = @($lockData.artifacts | Where-Object { $_.path.StartsWith('models/source/asr/en-parakeet-ctc/') -or $_.path.StartsWith('models/source/asr/en-moonshine-base/') })
if ($selected.Count -ne 7) { throw 'Expected seven pinned model, vocabulary and license files' }
foreach ($artifact in $selected) {
    $targetFile = Join-Path $projectRoot $artifact.path
    New-Item -ItemType Directory -Path (Split-Path -Parent $targetFile) -Force | Out-Null
    if (!(Test-Path -LiteralPath $targetFile)) {
        if ($artifact.source.EndsWith('.tar.bz2')) {
            $localSource = Join-Path $ParakeetDirectory (Split-Path -Leaf $targetFile)
            if (!(Test-Path -LiteralPath $localSource)) { throw "Extract the user-supplied Parakeet archive first: $localSource" }
            Copy-Item -LiteralPath $localSource -Destination $targetFile
        } else {
            & curl.exe -fL --retry 3 --silent --show-error $artifact.source -o $targetFile
            if ($LASTEXITCODE -ne 0) { throw "Download failed: $($artifact.id)" }
        }
    }
    if ((Get-Item -LiteralPath $targetFile).Length -ne $artifact.bytes -or
        (Get-FileHash -LiteralPath $targetFile).Hash.ToLowerInvariant() -ne $artifact.sha256) {
        throw "Pinned hash/size mismatch. Existing files are not overwritten: $targetFile"
    }
    Write-Output "Verified $($artifact.id)"
}
Copy-Item -LiteralPath "$projectRoot/models/source/asr/en-parakeet-ctc/LICENSE.txt" -Destination "$projectRoot/licenses/parakeet-CC-BY-4.0.txt"
Copy-Item -LiteralPath "$projectRoot/models/source/asr/en-moonshine-base/LICENSE.txt" -Destination "$projectRoot/licenses/moonshine-LICENSE.txt"
