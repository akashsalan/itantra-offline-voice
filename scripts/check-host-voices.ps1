$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:PATH = "C:\msys64\ucrt64\bin;$env:PATH"
$env:ESPEAK_DATA_PATH = "$projectRoot/.tools/espeak-host"
$outputDirectory = New-Item -ItemType Directory -Force "$projectRoot/.tools/voice-smoke"
$rows = @()
foreach ($language in @('bn','en','gu','hi','kn','ml','mr','or','ta','te')) {
    $wav = Join-Path $outputDirectory "$language.wav"
    & "$projectRoot/.tools/espeak-host/src/espeak-ng.exe" -v $language -w $wav '123'
    if ($LASTEXITCODE -ne 0) { throw "Host voice failed: $language" }
    $reader = [System.IO.BinaryReader]::new([System.IO.File]::OpenRead($wav))
    try {
        if ([System.Text.Encoding]::ASCII.GetString($reader.ReadBytes(4)) -ne 'RIFF') { throw 'Invalid WAV' }
        $reader.BaseStream.Position = 12
        $sampleRate = 0; $sampleBytes = 0; $nonzero = $false
        while ($reader.BaseStream.Position -lt $reader.BaseStream.Length) {
            $chunk = [System.Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
            $size = $reader.ReadUInt32()
            $next = $reader.BaseStream.Position + $size + ($size % 2)
            if ($chunk -eq 'fmt ') { $reader.ReadUInt16() | Out-Null; $reader.ReadUInt16() | Out-Null; $sampleRate = $reader.ReadUInt32() }
            if ($chunk -eq 'data') { $sampleBytes = $size; for ($i = 0; $i -lt $size / 2; $i++) { if ([Math]::Abs([int]$reader.ReadInt16()) -gt 16) { $nonzero = $true } } }
            $reader.BaseStream.Position = $next
        }
        if (!$nonzero -or $sampleRate -eq 0) { throw "No usable host PCM for $language" }
        $rows += [ordered]@{voice=$language; text='123'; sampleRate=$sampleRate; pcmBytes=$sampleBytes; nonzeroPcm=$nonzero}
    } finally { $reader.Dispose() }
}
New-Item -ItemType Directory -Force "$projectRoot/docs/results" | Out-Null
[ordered]@{kind='windows_host_cli_smoke_only'; measuredAtUtc=[DateTime]::UtcNow.ToString('o'); androidTested=$false; intelligibilityAssessed=$false; voices=$rows} | ConvertTo-Json -Depth 6 | Out-File -LiteralPath "$projectRoot/docs/results/host-voices.json" -Encoding utf8
Get-Content "$projectRoot/docs/results/host-voices.json"
