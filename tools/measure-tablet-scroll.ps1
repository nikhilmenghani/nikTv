param(
    [string]$Serial = "6889f3db",
    [string]$Adb = "C:/platform-tools/adb.exe",
    [int]$X = 1800,
    [int]$Top = 480,
    [int]$Bottom = 1250,
    [int]$DurationMs = 500
)
$ErrorActionPreference = "Stop"
# Start with the same category, loaded count and viewport on every comparison.
# Coordinates default to the landscape 2560x1600 tablet; override for other devices.
$foreground = & $Adb -s $Serial shell dumpsys activity activities
if (-not ($foreground -match 'ResumedActivity.*com.nikhil.niktv.debug')) {
    throw "Open NikTV Dev and the comparison category before measuring."
}
& $Adb -s $Serial shell dumpsys gfxinfo com.nikhil.niktv.debug reset | Out-Null
foreach ($direction in @('down', 'down', 'up', 'up')) {
    $from = if ($direction -eq 'down') { $Bottom } else { $Top }
    $to = if ($direction -eq 'down') { $Top } else { $Bottom }
    & $Adb -s $Serial shell input swipe $X $from $X $to $DurationMs
    if ($LASTEXITCODE -ne 0) { throw "Device input failed" }
}
& $Adb -s $Serial shell dumpsys gfxinfo com.nikhil.niktv.debug |
    Select-String 'Total frames|Janky frames:|percentile'

