param([string]$OutputPath = '')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
if (-not $OutputPath) {
    $OutputPath = Join-Path $projectRoot ('.tools/source-previews/itantra-code18-source-preview-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
}
$archivePath = [IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $archivePath) { throw 'Refusing to overwrite an existing source preview.' }
$include = @('app/src', 'app/build.gradle.kts', 'app/gradle.lockfile', 'protocol/src',
    'protocol/build.gradle.kts', 'protocol/gradle.lockfile', 'gradle', 'gradlew', 'gradlew.bat',
    'gradle.properties', 'build.gradle.kts', 'settings.gradle.kts', 'licenses', 'LICENSE',
    'THIRD_PARTY_NOTICES.md', 'scripts', 'third_party/espeak-ng', 'models/models.lock.json',
    'models/README.md', 'README.md', 'ITANTRA_PRD.md', 'DECISIONS.md',
    'docs/REQUIREMENTS_2026_09.md', 'docs/RELEASE_CHECKLIST.md',
    'docs/MOONSHINE_SMALL_STREAMING.md', 'docs/ENGLISH_LOW_END.md')
$files = @($include | ForEach-Object {
    $path = Join-Path $projectRoot $_
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing source input: $_" }
    Get-ChildItem -LiteralPath $path -File -Recurse
} | Where-Object {
    $relative = $_.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
    $relative -notmatch '(^|/)(\.git|\.gradle|build|__pycache__)(/|$)' -and
    $_.Extension -notin @('.apk', '.aar', '.onnx', '.nemo', '.itpack', '.ort', '.jks', '.keystore', '.pyc') -and
    -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint)
} | Sort-Object FullName -Unique)
$artifacts = @('app/build/outputs/apk/base/debug/app-base-debug.apk',
    'app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk') | ForEach-Object {
    $item = Get-Item -LiteralPath (Join-Path $projectRoot $_)
    [ordered]@{ path = $_; bytes = $item.Length; sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
New-Item -ItemType Directory -Path (Split-Path -Parent $archivePath) -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$zip = [IO.Compression.ZipFile]::Open($archivePath, [IO.Compression.ZipArchiveMode]::Create)
try {
    $entries = foreach ($file in $files) {
        $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $relative, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        [ordered]@{ path = $relative; bytes = $file.Length; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $manifest = [ordered]@{ schemaVersion = 1; completeCorrespondingSource = $false;
        createdUtc = [DateTime]::UtcNow.ToString('o'); releaseGate = 'docs/RELEASE_CHECKLIST.md';
        omittedPrerequisites = @('Matching sherpa/Moonshine native dependency sources and builds', 'Independent clean rebuild', 'Speech model binaries, supplied separately');
        apks = @($artifacts); files = @($entries) }
    $writer = [IO.StreamWriter]::new($zip.CreateEntry('source-preview-manifest.json').Open())
    try { $writer.Write(($manifest | ConvertTo-Json -Depth 7)) } finally { $writer.Dispose() }
} finally { $zip.Dispose() }
Get-Item -LiteralPath $archivePath | Select-Object FullName, Length
Get-FileHash -LiteralPath $archivePath -Algorithm SHA256
Write-Output 'Local source preview only. Corresponding-source release gates remain open.'
