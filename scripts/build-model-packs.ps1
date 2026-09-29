param(
    [ValidateSet('bn','en','gu','hi','kn','ml','mr','or','ta','te')][string[]]$Languages = @('en','hi'),
    [ValidateSet('moonshine-small','moonshine-tiny','parakeet')][string]$EnglishVariant = 'moonshine-small'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path -Parent $PSScriptRoot
$lockData = Get-Content -Raw "$projectRoot/models/models.lock.json" | ConvertFrom-Json
$packDirectory = New-Item -ItemType Directory -Force "$projectRoot/models/packs"
foreach ($language in $Languages) {
    $englishV2 = $language -eq 'en' -and $EnglishVariant -eq 'v2'
    $odia = $language -eq 'or'
    $sourcePrefix = if ($odia) { 'models/source/asr/or-ctc-experimental/' } elseif ($englishV2) { 'models/source/asr/en-2023-06-21/' } else { "models/source/asr/$language/" }
    $candidate = $language -eq 'en' -and $EnglishVariant -in @('parakeet','moonshine-base')
    if ($candidate) { $sourcePrefix = if ($EnglishVariant -eq 'parakeet') { 'models/source/asr/en-parakeet-ctc/' } else { 'models/source/asr/en-moonshine-base/' } }
    $streaming = $language -eq 'en' -and $EnglishVariant -in @('moonshine-small','moonshine-tiny')
    $streamingSize = if ($EnglishVariant -eq 'moonshine-tiny') { 'tiny' } else { 'small' }
    if ($streaming) { $sourcePrefix = "models/source/asr/en-moonshine-$streamingSize-streaming/" }
    $selected = @($lockData.artifacts | Where-Object { $_.path.StartsWith($sourcePrefix) -or ($language -ne 'en' -and $_.id -eq 'asr.indicconformer.shared_tokens') })
    if ($odia) {
        $audit = Get-Content -Raw "$projectRoot/docs/results/odia-export-host.json" | ConvertFrom-Json
        if (!$audit.host_smoke_passed -or $selected.Count -ne 2 -or @($selected | Where-Object { $_.path.EndsWith('.nemo') }).Count) { throw 'Odia conversion smoke checks must pass before packaging' }
        if (@($selected | Where-Object { $_.sha256 -eq $audit.int8_sha256 }).Count -ne 1) { throw 'Odia audit does not match pinned model' }
    }
    $files = @($selected | ForEach-Object {
        $path = Join-Path $projectRoot $_.path
        if ((Get-Item -LiteralPath $path).Length -ne $_.bytes -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $_.sha256) { throw "Invalid artifact: $path" }
        $name = Split-Path -Leaf $_.path
        if ($name -eq 'indic-tokens.txt') { $name = 'tokens.txt' }
        [ordered]@{path=$name; sha256=$_.sha256; bytes=$_.bytes}
    })
    $packId = if ($odia) { 'asr.indicconformer.or.ctc.experimental.v1' } elseif ($englishV2) { 'asr.zipformer.en.2023-06-21.int8' } elseif ($language -eq 'en') { 'asr.zipformer.en.20m' } else { "asr.indicconformer.$language.int8" }
    $engine = if ($language -eq 'en') { 'sherpa-online-transducer' } else { 'sherpa-offline-nemo-ctc' }
    $sourceRevision = if ($englishV2) { '9a65b6ea94c311ca770c2bf895b30f456a22d703' } else { 'content pinned by models.lock.json SHA-256; acquisition revision unresolved' }
    if ($odia) { $sourceRevision = 'NeMo 8dce88cf8e94963e2033c3137f7b9993b51db88a; notebook 9b07b2bfbfc6039c2e394545474e87c4cbafa19f; source checkpoint SHA-256 pinned in lock' }
    $license = if ($englishV2) { 'Apache-2.0; see THIRD_PARTY_NOTICES.md' } else { 'See THIRD_PARTY_NOTICES.md; distribution inventory pending' }
    $manifest = [ordered]@{schemaVersion=1; packId=$packId; language=$language; engine=$engine; sampleRate=16000; files=$files; sourceUrl=$selected[0].source; sourceRevision=$sourceRevision; license=$license; validated=$false}
    if ($odia) { $manifest['experimental'] = $true; $manifest['notes'] = 'Synthetic conversion/runtime checks only. Native-speaker accuracy acceptance pending. Requires app 0.3.3 or newer.' }
    $outputName = if ($odia) { 'or-experimental.itpack' } elseif ($englishV2) { 'en-v2.itpack' } else { "$language.itpack" }
    if ($candidate) {
        $manifest['packId'] = if ($EnglishVariant -eq 'parakeet') { 'asr.parakeet.en.110m.ctc.int8' } else { 'asr.moonshine.en.base.2026-02-27.quantized' }
        $manifest['engine'] = if ($EnglishVariant -eq 'parakeet') { 'sherpa-offline-nemo-ctc' } else { 'sherpa-offline-moonshine' }
        $manifest['sourceRevision'] = if ($EnglishVariant -eq 'parakeet') { 'User-supplied sherpa CTC INT8 export; file SHA-256 pinned in models.lock.json' } else { '8f4d6c58c03d40bcea40043bb7120a878f2bbef6' }
        $manifest['license'] = if ($EnglishVariant -eq 'parakeet') { 'CC-BY-4.0, NVIDIA and Suno. CTC INT8 sherpa export of https://huggingface.co/nvidia/parakeet-tdt_ctc-110m ; repackaged unchanged by iTantra. https://creativecommons.org/licenses/by/4.0/ ; LICENSE.txt included. No endorsement implied.' } else { 'MIT for English, Copyright (c) 2025 Useful Sensors Inc. (dba Moonshine AI). Full publisher LICENSE.txt included; English uses Section 1, not the non-English Community License.' }
        $manifest['displayName'] = 'English'
        $manifest['notes'] = 'English STT: Parakeet 110M CTC INT8, selected after user trial. Requires iTantra 0.5.0 or newer. Corpus accuracy not measured. TTS unchanged.'
        $outputName = 'en.itpack'
    }
    if ($streaming) {
        if ($selected.Count -ne 9) { throw 'Streaming packs require eight pinned model files and a license' }
        $manifest['packId'] = "asr.moonshine.en.$streamingSize.streaming.2026-08-21.quantized"
        $manifest['engine'] = "moonshine-$streamingSize-streaming"
        $manifest['sourceRevision'] = 'Moonshine v0.1.5 commit 234f60faa0eb388b01cdf7e60aca232af37aefda; quantized_26_08_21; publisher CRC32C checked and SHA-256 pinned'
        $manifest['license'] = 'MIT, Copyright (c) 2025 Useful Sensors Inc. (dba Moonshine AI). All streaming models use Section 1 of the included LICENSE.txt.'
        $manifest['displayName'] = 'English - Moonshine Small Streaming'
        $manifest['notes'] = 'Small Streaming, not legacy Base. Requires iTantra 0.6.2 or newer. Accuracy acceptance pending. TTS unchanged.'
        if ($streamingSize -eq 'tiny') {
            $outputName = 'en-low-end.itpack'
            $manifest['displayName'] = 'English - low-end devices'
            $manifest['notes'] = 'Tiny Streaming, MIT. Smaller speed-first alternative; may make more errors than Small. Low-end performance/accuracy acceptance pending. Requires iTantra 0.6.3 or newer. TTS unchanged.'
        }
    }
    $destination = Join-Path $packDirectory $outputName
    if (Test-Path -LiteralPath $destination) { throw "$destination already exists; preserve or explicitly remove it before rebuilding" }
    $archive = [System.IO.Compression.ZipFile]::Open($destination, 'Create')
    try {
        $entry = $archive.CreateEntry('manifest.json')
        $writer = [System.IO.StreamWriter]::new($entry.Open(), [System.Text.UTF8Encoding]::new($false))
        try { $writer.Write(($manifest | ConvertTo-Json -Depth 8)) } finally { $writer.Dispose() }
        for ($i = 0; $i -lt $selected.Count; $i++) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $projectRoot $selected[$i].path), $files[$i].path, [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
        }
    } finally { $archive.Dispose() }
    Get-Item -LiteralPath $destination | Select-Object Name,Length
}
