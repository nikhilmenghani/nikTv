param(
    [Parameter(Mandatory = $true)][string]$TvIp,
    [string]$SdkPath = 'D:\workspace\tools\tizen-sdk',
    [string]$ProfileName = 'Nikhil',
    [string]$DeviceProfilePath,
    [switch]$Launch
)
$ErrorActionPreference = 'Stop'
if (-not $DeviceProfilePath) {
    $DeviceProfilePath = Join-Path $env:USERPROFILE "SamsungCertificate\$ProfileName\device-profile.xml"
}
if (-not (Test-Path -LiteralPath $DeviceProfilePath)) {
    throw 'Create a Samsung TV certificate including this TV DUID before installing.'
}
$sdb = Join-Path $SdkPath 'tools\sdb.exe'
$tizen = Join-Path $SdkPath 'tools\ide\bin\tizen.bat'
$serial = "${TvIp}:26101"
$remote = '/home/owner/share/tmp/sdk_tools'
function Invoke-CheckedTool {
    param([string]$Tool, [string[]]$ToolArgs)
    & $Tool @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "Tool failed: $Tool" }
}
Invoke-CheckedTool $sdb @('connect', $serial)
Invoke-CheckedTool $tizen @('build-web', '--', $PSScriptRoot)
Invoke-CheckedTool $tizen @('package', '-t', 'wgt', '-s', $ProfileName, '--', (Join-Path $PSScriptRoot '.buildResult'))
$package = Join-Path $PSScriptRoot '.buildResult\NikTV Preview.wgt'
# Samsung TV developer authorization: upload the generated public device profile,
# never the author/distributor private keys or passwords.
Invoke-CheckedTool $sdb @('-s', $serial, 'push', $DeviceProfilePath, "$remote/device-profile.xml")
Invoke-CheckedTool $sdb @('-s', $serial, 'push', $package, "$remote/NikTV.wgt")
$result = & $sdb -s $serial shell 0 vd_appinstall NikTVTizen "$remote/NikTV.wgt" 2>&1
$exitCode = $LASTEXITCODE
$result | Write-Output
if ($exitCode -ne 0 -or ($result -join "`n") -notmatch 'cmd_ret:0' -or ($result -join "`n") -notmatch 'install completed') {
    throw 'TV did not confirm successful installation. Inspect the installer response above.'
}
if ($Launch) {
    $result = & $tizen run -s $serial -p NikTVTizen.NikTV 2>&1
    $exitCode = $LASTEXITCODE
    $result | Write-Output
    if ($exitCode -ne 0 -or ($result -join "`n") -notmatch 'successfully launched') {
        throw 'TV did not confirm successful launch.'
    }
}
