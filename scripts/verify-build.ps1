param([switch]$IncludePreloaded)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $tasks = @(':app:assembleBaseDebug', ':app:testBaseDebugUnitTest', ':app:lintBaseDebug', ':app:assembleBaseDebugAndroidTest')
    if ($IncludePreloaded) { $tasks += ':app:assembleDemoPreloadedDebug' }
    & .\gradlew.bat @tasks --console=plain 2>&1 | Tee-Object -FilePath 'docs/results/build.log'
    if ($LASTEXITCODE -ne 0) { throw 'Android build/checks failed; see docs/results/build.log' }
    $totals = @{tests=0; failures=0; errors=0; skipped=0}
    $results = @(Get-ChildItem 'app/build/test-results/testBaseDebugUnitTest' -Filter 'TEST-*.xml' -File)
    if (!$results.Count) { throw 'No unit test reports' }
    foreach ($file in $results) {
        $xml = [xml](Get-Content -Raw -LiteralPath $file.FullName)
        foreach ($field in @('tests','failures','errors','skipped')) { $totals[$field] += [int]$xml.testsuite.$field }
    }
    if ($totals.failures -or $totals.errors) { throw 'Unit test failures' }
    $lint = [xml](Get-Content -Raw 'app/build/reports/lint-results-baseDebug.xml')
    $errors = @($lint.issues.issue | Where-Object severity -eq 'Error')
    $warnings = @($lint.issues.issue | Where-Object severity -eq 'Warning')
    if ($errors.Count) { throw 'Lint errors' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $variants = @('base')
    if ($IncludePreloaded) { $variants += 'demoPreloaded' }
    $apks = @(foreach ($variant in $variants) {
        $path = "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk"
        $apk = Get-Item -LiteralPath $path
        $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
        try {
            $models = @($zip.Entries | Where-Object { $_.FullName -match '\.(onnx|ort|nemo|itpack)$' } | ForEach-Object FullName)
            $native = @($zip.Entries | Where-Object { $_.FullName.StartsWith('lib/') } | ForEach-Object FullName)
            if (@($native | Where-Object { !$_.StartsWith('lib/arm64-v8a/') }).Count) { throw 'Unexpected ABI' }
            $vadAsset = 'assets/vad/silero_vad.int8.onnx'
            if ($variant -eq 'base' -and @($models | Where-Object { $_ -ne $vadAsset }).Count) { throw 'ASR bundled in base APK' }
            if ($vadAsset -notin $models) { throw 'PTT VAD model missing' }
            if ($variant -eq 'demoPreloaded') {
                $expected = @('assets/starter-packs/en.itpack','assets/starter-packs/en-low-end.itpack','assets/starter-packs/hi.itpack', $vadAsset)
                if (@(Compare-Object $expected $models).Count) { throw 'Unexpected preloaded models' }
            }
            [ordered]@{variant=$variant; path=$path; bytes=$apk.Length;
                sha256=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant();
                bundledModels=$models; nativeLibraries=$native}
        } finally { $zip.Dispose() }
    })
    [ordered]@{
        measuredAtUtc=[DateTime]::UtcNow.ToString('o'); kind='host_build_validation';
        apks=$apks; unitTests=$totals; lintErrors=$errors.Count; lintWarnings=$warnings.Count;
        instrumentation='compiled; run separately and consult android-local-groups-tests.txt for the latest LAN/legacy/native-TTS suite';
        physicalTwoPhoneRadio='legacy_EN_HI_user_reported_pass; new_LAN_groups_multi_phone_acceptance_pending';
        speechAccuracy='no_corpus_WER_or_listening_score_claimed'
    } | ConvertTo-Json -Depth 8 | Out-File 'docs/results/build-summary.json' -Encoding utf8
    Get-Content 'docs/results/build-summary.json'
} finally { Pop-Location }
