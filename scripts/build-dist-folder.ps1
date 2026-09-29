# Assembles D:\SIH\dist for sideloading onto test phones.
#
# The .itpack files total ~3 GB and this drive has limited free space, so they are
# hardlinked rather than copied: a hardlink costs no extra bytes on the same NTFS
# volume, and copying the folder to a phone or USB stick materialises full files.
# APKs are real copies so they stay a snapshot even after the next Gradle build
# overwrites app/build/outputs.
#
# Safe to re-run: it rebuilds dist from scratch each time.

$ErrorActionPreference = 'Stop'
$root = 'D:\SIH'
$dist = Join-Path $root 'dist'

if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Path $dist, "$dist\apk", "$dist\packs\speech-to-text", "$dist\packs\voices" | Out-Null

$version = (Select-String -Path "$root\app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$code = (Select-String -Path "$root\app\build.gradle.kts" -Pattern 'versionCode\s*=\s*(\d+)').Matches[0].Groups[1].Value

# --- APKs: real copies ---
$apks = @(
    @{ src = "$root\app\build\outputs\apk\base\debug\app-base-debug.apk"
       dst = "$dist\apk\itantra-base-$version-code$code.apk" }
    @{ src = "$root\app\build\outputs\apk\demoPreloaded\debug\app-demoPreloaded-debug.apk"
       dst = "$dist\apk\itantra-preloaded-$version-code$code.apk" }
)
foreach ($apk in $apks) {
    if (-not (Test-Path $apk.src)) { throw "Missing APK: $($apk.src). Run the assemble tasks first." }
    Copy-Item $apk.src $apk.dst
}

# --- Packs: hardlinks, split by what they are ---
$linked = 0
foreach ($pack in Get-ChildItem "$root\models\packs" -Filter *.itpack) {
    $folder = if ($pack.Name -like '*-voice.itpack') { 'voices' } else { 'speech-to-text' }
    New-Item -ItemType HardLink -Path "$dist\packs\$folder\$($pack.Name)" -Target $pack.FullName | Out-Null
    $linked++
}

# --- README: written here so the folder is reproducible from this script alone ---
$readme = [IO.File]::ReadAllText("$root\scripts\dist-README.txt").
    Replace('{{VERSION}}', $version).
    Replace('{{CODE}}', $code).
    Replace('{{DATE}}', (Get-Date -Format 'd MMMM yyyy'))
[IO.File]::WriteAllText("$dist\README.txt", $readme, (New-Object Text.UTF8Encoding $false))

# --- Checksums: lets anyone who downloads this folder (for example from a shared
# drive) confirm every file arrived intact. Paths are relative and use forward
# slashes so the file also works with sha256sum on Linux/macOS.
$sums = Get-ChildItem $dist -Recurse -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    Sort-Object FullName | ForEach-Object {
        $rel = $_.FullName.Substring($dist.Length + 1).Replace('\', '/')
        '{0}  {1}' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $rel
    }
[IO.File]::WriteAllLines("$dist\SHA256SUMS.txt", [string[]]$sums, (New-Object Text.UTF8Encoding $false))

Write-Host "APKs copied:      $($apks.Count)"
Write-Host "Packs hardlinked: $linked"
Write-Host "Version:          $version (code $code)"
Write-Host "Folder:           $dist"
