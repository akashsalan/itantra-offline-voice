param([string[]]$Languages = @('en','hi','bn','gu','kn','ml','mr','or','ta','te'), [string]$ReportName)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path -Parent $PSScriptRoot
$lockData = Get-Content -Raw "$projectRoot/models/models.lock.json" | ConvertFrom-Json
$profiles = @(
    @{name='en.itpack'; id='asr.moonshine.en.small.streaming.2026-08-21.quantized'; language='en'; prefix='models/source/asr/en-moonshine-small-streaming/'; engine='moonshine-small-streaming'},
    @{name='en-low-end.itpack'; id='asr.moonshine.en.tiny.streaming.2026-08-21.quantized'; language='en'; prefix='models/source/asr/en-moonshine-tiny-streaming/'; engine='moonshine-tiny-streaming'},
    @{name='hi.itpack'; id='asr.indicconformer.hi.int8'; language='hi'; prefix='models/source/asr/hi/'}
)
foreach ($code in @('bn','gu','kn','ml','mr','ta','te')) {
    if (Test-Path -LiteralPath "$projectRoot/models/packs/$code.itpack") {
        $profiles += @{name="$code.itpack"; id="asr.indicconformer.$code.int8"; language=$code; prefix="models/source/asr/$code/"}
    }
}
if (Test-Path -LiteralPath "$projectRoot/models/packs/or-experimental.itpack") {
    $profiles += @{name='or-experimental.itpack'; id='asr.indicconformer.or.ctc.experimental.v1'; language='or'; prefix='models/source/asr/or-ctc-experimental/'}
}
$rows = @(foreach ($profile in ($profiles | Where-Object { $_.language -in $Languages })) {
    $path = Join-Path $projectRoot "models/packs/$($profile.name)"
    $zip = [System.IO.Compression.ZipFile]::OpenRead($path)
    try {
        if ($zip.Entries[0].FullName -ne 'manifest.json') { throw 'Manifest must be first' }
        $reader = [System.IO.StreamReader]::new($zip.Entries[0].Open())
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        $expected = @($lockData.artifacts | Where-Object { $_.path.StartsWith($profile.prefix) -or ($profile.language -ne 'en' -and $_.id -eq 'asr.indicconformer.shared_tokens') })
        if ($manifest.packId -ne $profile.id -or $manifest.language -ne $profile.language -or $manifest.schemaVersion -ne 1 -or $manifest.sampleRate -ne 16000) { throw 'Wrong manifest identity' }
        if ($profile.language -eq 'or' -and (!$manifest.experimental -or $manifest.validated)) { throw 'Odia must be marked experimental, not validated' }
        $engine = if ($profile.engine) { $profile.engine } elseif ($profile.language -eq 'en') { 'sherpa-online-transducer' } else { 'sherpa-offline-nemo-ctc' }
        if ($manifest.engine -ne $engine) { throw 'Wrong pack engine' }
        if ($zip.Entries.Count -ne $expected.Count + 1 -or $manifest.files.Count -ne $expected.Count) { throw 'Unexpected pack file count' }
        foreach ($artifact in $expected) {
            $name = Split-Path -Leaf $artifact.path
            if ($name -eq 'indic-tokens.txt') { $name = 'tokens.txt' }
            $entries = @($zip.Entries | Where-Object FullName -eq $name)
            $declared = @($manifest.files | Where-Object path -eq $name)
            if ($entries.Count -ne 1 -or $declared.Count -ne 1 -or $entries[0].Length -ne $artifact.bytes -or $declared[0].bytes -ne $artifact.bytes -or $declared[0].sha256 -ne $artifact.sha256) { throw "Pack metadata mismatch: $name" }
            $stream = $entries[0].Open()
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $actual = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
            finally { $sha.Dispose(); $stream.Dispose() }
            if ($actual -ne $artifact.sha256) { throw "Pack content mismatch: $name" }
        }
    } finally { $zip.Dispose() }
    [ordered]@{name=$profile.name; packId=$profile.id; bytes=(Get-Item -LiteralPath $path).Length; sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant(); modelFiles=$expected.Count; matchesLock=$true}
})
if (!$ReportName) { $ReportName = if ($Languages.Count -lt 10) { 'pack-integrity-focused.json' } else { 'pack-integrity.json' } }
if ($ReportName -notmatch '^[a-zA-Z0-9-]+\.json$') { throw 'ReportName must be a simple JSON filename' }
[ordered]@{kind='host_zip_integrity_not_android_import_test'; measuredAtUtc=[DateTime]::UtcNow.ToString('o'); packs=$rows} |
    ConvertTo-Json -Depth 5 | Out-File -LiteralPath "$projectRoot/docs/results/$reportName" -Encoding utf8
$rows | ForEach-Object { [pscustomobject]$_ } | Format-Table name,bytes,modelFiles,matchesLock
