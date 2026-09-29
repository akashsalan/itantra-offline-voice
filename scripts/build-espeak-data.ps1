param([string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }), [string]$CompilerBin = 'C:\msys64\ucrt64\bin')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$projectRoot = $projectRoot.Replace('\', '/')
$Sdk = $Sdk.Replace('\', '/')
$CompilerBin = $CompilerBin.Replace('\', '/')
$env:PATH = "$CompilerBin;$env:PATH"
$cmakeExe = Join-Path $Sdk 'cmake/3.22.1/bin/cmake.exe'
$ninjaExe = Join-Path $Sdk 'cmake/3.22.1/bin/ninja.exe'
& $cmakeExe -S "$projectRoot/third_party/espeak-ng" -B "$projectRoot/.tools/espeak-host" -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninjaExe" "-DCMAKE_C_COMPILER=$CompilerBin/gcc.exe" "-DCMAKE_CXX_COMPILER=$CompilerBin/g++.exe" -DCMAKE_BUILD_TYPE=Release -DENABLE_TESTS=OFF -DBUILD_TESTING=OFF -DUSE_ASYNC=OFF -DUSE_MBROLA=OFF -DUSE_LIBSONIC=OFF -DUSE_LIBPCAUDIO=OFF
if ($LASTEXITCODE -ne 0) { throw 'eSpeak host configuration failed' }
& $cmakeExe --build "$projectRoot/.tools/espeak-host" --target data --parallel 4
if ($LASTEXITCODE -ne 0) { throw 'eSpeak voice data compilation failed' }
