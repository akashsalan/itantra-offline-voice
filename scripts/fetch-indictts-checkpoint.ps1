<#
Downloads one AI4Bharat Indic-TTS checkpoint archive, keeps only the HiFi-GAN
vocoder files, then deletes the archive. Peak disk use stays near 1.6 GB instead
of 12.6 GB for all languages.

  powershell -File scripts/fetch-indictts-checkpoint.ps1 -Language hi
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Language,
    [string]$Root,
    [switch]$IncludeAcoustic
)

if (-not $Root) {
    $Root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }
}

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$work = Join-Path $Root '.tools\indictts-vocoders'
$staging = Join-Path $work "raw\$Language"
$archive = Join-Path $work "$Language.zip"
$uri = "https://github.com/AI4Bharat/Indic-TTS/releases/download/v1-checkpoints-release/$Language.zip"

New-Item -ItemType Directory -Force -Path $work, $staging | Out-Null

if (Get-ChildItem $staging -Recurse -File -ErrorAction SilentlyContinue) {
    Write-Host "$Language already staged at $staging"
}
else {
    if (-not (Test-Path $archive)) {
        Write-Host "downloading $Language.zip ..."
        # curl streams to disk and resumes. Invoke-WebRequest buffers the whole
        # body in memory, which is not viable for a 1.4 GB archive.
        $curl = Join-Path $env:SystemRoot 'System32\curl.exe'
        if (-not (Test-Path $curl)) { $curl = 'curl.exe' }
        $expected = (Invoke-RestMethod 'https://api.github.com/repos/AI4Bharat/Indic-TTS/releases/tags/v1-checkpoints-release' `
                -Headers @{ 'User-Agent' = 'itantra' }).assets |
            Where-Object name -eq "$Language.zip" | Select-Object -ExpandProperty size
        # curl can exit 0 on a dropped connection, so resume until the byte count
        # matches the release asset exactly. A truncated zip fails to open with a
        # misleading "split or spanned archive" error.
        for ($attempt = 1; $attempt -le 6; $attempt++) {
            & $curl -L --fail --no-progress-meter --retry 3 --retry-delay 5 -C - -o $archive $uri
            $have = if (Test-Path $archive) { (Get-Item $archive).Length } else { 0 }
            if ($expected -and $have -eq $expected) { break }
            Write-Host "  incomplete: $have / $expected bytes; resuming (attempt $attempt)"
            if ($attempt -eq 6) { throw "Could not download a complete $Language.zip" }
        }
    }
    $final = (Get-Item $archive).Length
    Write-Host "archive bytes: $final"
    $sizeMb = [math]::Round((Get-Item $archive).Length / 1MB, 1)
    Write-Host "archive $Language.zip = $sizeMb MB; listing entries"

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        # Record the full layout once so the export step is not guessing.
        $zip.Entries | ForEach-Object { "{0}`t{1}" -f $_.Length, $_.FullName } |
            Set-Content (Join-Path $work "$Language-entries.txt") -Encoding utf8

        # Keep vocoder weights/config plus any config.json for mel parameters.
        # -IncludeAcoustic also keeps the FastPitch weights, needed for languages
        # EchoBharat never published (Odia), where we export both stages ourselves.
        $wanted = $zip.Entries | Where-Object {
            $_.Name -and (
                $_.FullName -match '(?i)(hifigan|vocoder)' -or
                $_.Name -match '(?i)^config\.json$' -or
                ($IncludeAcoustic -and $_.FullName -match '(?i)fastpitch')
            )
        }
        foreach ($entry in $wanted) {
            $target = Join-Path $staging ($entry.FullName -replace '/', '\')
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            Write-Host ("  kept {0,12:N0}  {1}" -f $entry.Length, $entry.FullName)
        }
    }
    finally { $zip.Dispose() }

    Remove-Item $archive -Force
    Write-Host "archive deleted; kept files under $staging"
}

Get-ChildItem $staging -Recurse -File |
    Select-Object @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 2) } }, FullName |
    Format-Table -AutoSize
