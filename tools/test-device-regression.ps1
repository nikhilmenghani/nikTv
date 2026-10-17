param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repoRoot
try {
    $deviceState = (& adb -s $Serial get-state | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') { throw 'Select an actually connected, authorized ADB device.' }
    $api = [int]((& adb -s $Serial shell getprop ro.build.version.sdk | Out-String).Trim())
    if ($api -lt 29) { throw 'The full suite includes scoped MediaStore cleanup tests and requires API 29 or newer.' }
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true --offline
    if ($LASTEXITCODE -ne 0) { throw 'Regression build failed.' }
    $metadata = Get-Content -LiteralPath app\build-regression\outputs\apk\debug\output-metadata.json -Raw | ConvertFrom-Json
    $testMetadata = Get-Content -LiteralPath app\build-regression\outputs\apk\androidTest\debug\output-metadata.json -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.nikhil.niktv.regression' -or $testMetadata.applicationId -ne 'com.nikhil.niktv.regression.test') {
        throw 'Refusing to install APKs outside the isolated regression packages.'
    }
    & adb -s $Serial install -r app\build-regression\outputs\apk\debug\app-universal-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Isolated app installation failed.' }
    & adb -s $Serial install -r app\build-regression\outputs\apk\androidTest\debug\app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
    $result = (& adb -s $Serial shell am instrument -w com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner | Out-String)
    Write-Output $result
    if ($LASTEXITCODE -ne 0 -or $result -notmatch '(?m)^OK \(\d+ tests\)' -or $result -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') {
        throw 'Device regressions did not pass. Do not commit based on the build alone.'
    }
} finally { Pop-Location }
