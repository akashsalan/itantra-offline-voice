param([string]$Serial, [string]$TapText, [string]$Package = 'org.itantra.app')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$adbExe = (Join-Path $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }) "platform-tools\adb.exe")
if (!$Serial) {
    $devices = @(& $adbExe devices | Where-Object { $_ -match '^\S+\s+device$' })
    if ($devices.Count -ne 1) { throw 'Select exactly one authorized phone with -Serial' }
    $Serial = ($devices[0] -split '\s+')[0]
}
$dump = & $adbExe -s $Serial shell uiautomator dump /data/local/tmp/itantra-ui.xml
if ($dump -notmatch 'dumped to') { throw 'UI hierarchy not available; keep the phone unlocked' }
$document = [xml]((& $adbExe -s $Serial shell cat /data/local/tmp/itantra-ui.xml) -join "`n")
$nodes = @($document.SelectNodes('//node') | Where-Object { $_.package -eq $Package -and ($_.text -or $_.'content-desc') })
if ($TapText) {
    $match = @($nodes | Where-Object { $_.text -eq $TapText -or $_.'content-desc' -eq $TapText })
    if ($match.Count -ne 1) { throw "Expected one visible control: $TapText" }
    if ($match[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Invalid control bounds' }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    if ($x -eq 0 -and $y -eq 0) { throw 'Control is off screen' }
    & $adbExe -s $Serial shell input tap $x $y
    "Tapped: $TapText"
} else {
    $nodes | ForEach-Object { [PSCustomObject]@{Text=$_.text; Description=$_.'content-desc'; Id=$_.'resource-id'; Bounds=$_.bounds} } | Format-Table -AutoSize
}
