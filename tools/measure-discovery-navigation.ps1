param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [ValidateSet('Remote','Touch')][string]$InputMode = 'Remote',
    [int]$Width = 2560,
    [int]$Height = 1600,
    [string]$Adb = 'C:/platform-tools/adb.exe'
)
$ErrorActionPreference = 'Stop'
# Open the intended screen and position it identically before each run. Never record
# video during timing. Remote runs start on hero Play (trial) or first tile (classic).
$window = (& $Adb -s $Serial shell dumpsys window) -join "`n"
if ($window -notmatch 'mCurrentFocus[^\r\n]*com\.nikhil\.niktv\.debug') {
    throw 'NikTV Dev must be foreground and unlocked before measuring.'
}
& $Adb -s $Serial shell dumpsys gfxinfo com.nikhil.niktv.debug reset | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Unable to reset frame statistics.' }
if ($InputMode -eq 'Remote') {
    & $Adb -s $Serial shell 'for key in 22 21 20 22 21 20 19 19 22 21 20 22 21 20 19 19; do input keyevent $key; sleep 0.2; done'
} else {
    $x = [int]($Width * 0.75)
    $top = [int]($Height * 0.32)
    $bottom = [int]($Height * 0.78)
    foreach ($up in @($true, $true, $false, $false)) {
        $from = if ($up) { $bottom } else { $top }
        $to = if ($up) { $top } else { $bottom }
        & $Adb -s $Serial shell input swipe $x $from $x $to 450
        if ($LASTEXITCODE -ne 0) { throw 'Device input failed.' }
    }
}
if ($LASTEXITCODE -ne 0) { throw 'Device navigation input failed.' }
& $Adb -s $Serial shell dumpsys gfxinfo com.nikhil.niktv.debug framestats | Set-Content -LiteralPath $OutputPath
& $Adb -s $Serial shell dumpsys meminfo com.nikhil.niktv.debug | Set-Content -LiteralPath ($OutputPath + '.memory.txt')
Get-Content -LiteralPath $OutputPath | Select-String 'Total frames|Janky frames:|percentile|Frame deadline missed' |
    ForEach-Object { $_.Line } | Select-Object -Unique
