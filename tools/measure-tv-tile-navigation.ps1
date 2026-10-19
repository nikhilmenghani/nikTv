param(
    [string]$Serial = "192.168.2.19:5555",
    [string]$Adb = "C:/platform-tools/adb.exe",
    [Parameter(Mandatory = $true)][string]$OutputPath
)
$ErrorActionPreference = "Stop"
# Open the same two-column channel grid and focus its first tile before each run.
# This deliberately reverses direction and returns to the original tile.
$foreground = (& $Adb -s $Serial shell dumpsys activity activities) -join "`n"
if ($foreground -notmatch '(?s)mResumedActivity.{0,160}com\.nikhil\.niktv\.debug/com\.nikhil\.niktv\.MainActivity') {
    throw "Open the NikTV channel comparison grid before measuring."
}
& $Adb -s $Serial shell dumpsys gfxinfo com.nikhil.niktv.debug reset | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to reset frame statistics" }
$sequence = 'for key in 22 21 20 20 20 20 20 19 19 19 19 19 22 21 20 20 20 20 20 19 19 19 19 19; do input keyevent $key; sleep 0.15; done'
& $Adb -s $Serial shell $sequence
if ($LASTEXITCODE -ne 0) { throw "Device navigation input failed" }
$frames = & $Adb -s $Serial shell dumpsys gfxinfo com.nikhil.niktv.debug framestats
if ($LASTEXITCODE -ne 0) { throw "Unable to collect frame statistics" }
$frames | Set-Content -LiteralPath $OutputPath
$frames | Select-String 'Total frames|Janky frames:|percentile|Frame deadline missed' | ForEach-Object { $_.Line } | Select-Object -Unique
