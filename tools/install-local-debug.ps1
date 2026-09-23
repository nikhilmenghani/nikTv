param(
    [string]$Serial,
    [string]$Device,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$propertiesPath = Join-Path $env:USERPROFILE '.gradle\gradle.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    throw "User Gradle properties were not found at $propertiesPath."
}

$tokenLine = Get-Content -LiteralPath $propertiesPath |
    Where-Object { $_ -match '^\s*G_TOKEN\s*=' } |
    Select-Object -Last 1
if (-not $tokenLine) { throw 'G_TOKEN is missing from user Gradle properties.' }
$token = ($tokenLine -split '=', 2)[1].Trim().Trim('"', "'")
if ([string]::IsNullOrWhiteSpace($token)) { throw 'G_TOKEN is empty in user Gradle properties.' }

if (-not $SkipBuild) {
    & (Join-Path $repoRoot 'gradlew.bat') assembleDebug --offline
    if ($LASTEXITCODE -ne 0) { throw 'Debug build failed.' }
}

$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-universal-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'The universal debug APK was not found. Run without -SkipBuild.' }

if (-not $Serial) {
    $connected = @(& adb devices -l | Where-Object { $_ -match '^(.+?)\s+device(?:\s|$)' } |
        ForEach-Object {
            $deviceSerial = $Matches[1].Trim()
            $hardwareId = if ($deviceSerial -match '^adb-([^-]+)-') { $Matches[1] } else { $deviceSerial }
            $marketName = (& adb -s $deviceSerial shell getprop ro.product.marketname).Trim()
            $model = (& adb -s $deviceSerial shell getprop ro.product.model).Trim()
            $manufacturer = (& adb -s $deviceSerial shell getprop ro.product.manufacturer).Trim()
            [pscustomobject]@{
                Serial = $deviceSerial
                HardwareId = $hardwareId
                Name = if ($marketName) { $marketName } else { "$manufacturer $model".Trim() }
                Model = $model
                Connection = if ($deviceSerial -eq $hardwareId) { 'USB' } else { 'Wi-Fi' }
            }
        })
    if (-not $connected) { throw 'No authorized ADB device is connected.' }
    $devices = @($connected | Group-Object HardwareId | ForEach-Object {
        $_.Group | Sort-Object { if ($_.Connection -eq 'USB') { 0 } else { 1 } } | Select-Object -First 1
    })
    for ($index = 0; $index -lt $devices.Count; $index++) {
        Write-Output "[$($index + 1)] $($devices[$index].Name) ($($devices[$index].Model), $($devices[$index].Connection))"
    }
    if ($Device) {
        $matches = @($devices | Where-Object {
            $_.Name -like "*$Device*" -or $_.Model -like "*$Device*" -or $_.HardwareId -like "*$Device*"
        })
        if ($Device -match '^\d+$' -and [int]$Device -ge 1 -and [int]$Device -le $devices.Count) {
            $matches = @($devices[[int]$Device - 1])
        }
        if ($matches.Count -ne 1) { throw "Device '$Device' did not match exactly one connected device." }
        $Serial = $matches[0].Serial
    } elseif ($devices.Count -eq 1) {
        $Serial = $devices[0].Serial
    } else {
        $selection = Read-Host 'Select device number'
        if ($selection -notmatch '^\d+$' -or [int]$selection -lt 1 -or [int]$selection -gt $devices.Count) {
            throw 'Enter a valid device number or pass -Device with part of its name.'
        }
        $Serial = $devices[[int]$selection - 1].Serial
    }
}

& adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }

# The token travels over ADB stdin into debug-app-private storage. It is never
# placed in the APK, command arguments, or a device-wide temporary directory.
$token | & adb -s $Serial shell "run-as com.nikhil.niktv.debug sh -c 'umask 077; cat > files/local_github_token_import'" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not provision the token through ADB.' }

& adb -s $Serial shell am force-stop com.nikhil.niktv.debug | Out-Null
& adb -s $Serial shell am start -n com.nikhil.niktv.debug/com.nikhil.niktv.MainActivity | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'The app was installed but could not be launched to import the token.' }
Write-Output "NikTV Debug installed on $Serial; local token import was submitted to the app."
