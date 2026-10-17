param(
    [Parameter(Mandatory=$true)][ValidateSet('Flow','SmartTube')][string]$App,
    [string]$Ref,
    [switch]$Apply
)
$ErrorActionPreference='Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$importRoot = Join-Path $repoRoot '.upstream-import'
$checkout = Join-Path $importRoot ([guid]::NewGuid().ToString())
$url = if($App -eq 'Flow'){'https://github.com/Flow-Tube/Flow.git'}else{'https://github.com/yuliskov/SmartTube.git'}
if(-not $Ref){$Ref=if($App -eq 'Flow'){'main'}else{'master'}}
$destination = Join-Path $repoRoot $(if($App -eq 'Flow'){'niktube-flow'}else{'niktube-smarttube'})
New-Item -ItemType Directory -Path $importRoot -Force | Out-Null
try {
    & git clone --depth 1 --branch $Ref $url $checkout
    if($LASTEXITCODE -ne 0){throw 'Upstream checkout failed'}
    $revision = & git -C $checkout rev-parse HEAD
    $receipt = Get-Content (Join-Path $destination 'upstream-provenance.json') -Raw | ConvertFrom-Json
    Write-Output "Installed source: $($receipt.revision)"
    Write-Output "Candidate source: $revision"
    if(-not $Apply){Write-Output 'Check complete. Use -Apply to import a reviewed release, then rebuild and test NikTV.'; return}
    if($App -eq 'SmartTube') {
        & git -C $checkout submodule update --init --recursive --depth 1
        if($LASTEXITCODE -ne 0){throw 'Upstream companion modules could not be fetched'}
    }
    $prefixes = if($App -eq 'Flow'){@('app/src/','app/schemas/','app/build.gradle.kts','app/proguard-rules.pro','gradle/','License')}else{@('smarttubetv/','common/','chatkit/','leanbackassistant/','leanback-1.0.0/','fragment-1.1.0/','filepicker-lib/','doubletapplayerview/','slidableactivity/','exoplayer-amzn-2.10.6/','LICENSE')}
    $tracked = & git -C $checkout ls-files
    foreach($relative in $tracked) {
        if(-not ($prefixes | Where-Object {$relative.StartsWith($_)}) -or $relative -match '/build/'){continue}
        $sourceFile=Join-Path $checkout $relative
        if(-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)){continue}
        $target=Join-Path $destination $relative
        New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
        Copy-Item -LiteralPath $sourceFile -Destination $target
    }
    if($App -eq 'Flow') {
        $application=Join-Path $destination 'app/src/main/java/io/github/aedev/flow/FlowApplication.kt'
        $text=Get-Content -LiteralPath $application -Raw
        $text=$text.Replace('import dagger.hilt.android.HiltAndroidApp','').Replace('@HiltAndroidApp','').Replace('class FlowApplication :','open class FlowApplication :')
        $text=$text.Replace('appContext = applicationContext',"appContext = applicationContext`n        if (android.os.Build.VERSION.SDK_INT < 26) return")
        Set-Content -LiteralPath $application -Value $text -NoNewline
        & python (Join-Path $PSScriptRoot 'prepare-flow.py')
        if($LASTEXITCODE -ne 0){throw 'Flow manifest adaptation failed'}
    } else {
        foreach($module in @('SharedModules','MediaServiceCore')) {
            $moduleRoot=Join-Path $checkout $module
            $moduleRevision = & git -C $moduleRoot rev-parse HEAD
            if($LASTEXITCODE -ne 0){throw 'Companion revision lookup failed'}
            if($module -eq 'SharedModules'){$receipt.sharedModulesRevision=$moduleRevision}else{$receipt.mediaServiceCoreRevision=$moduleRevision}
            foreach($relative in (& git -C $moduleRoot ls-files)) {
                $sourceFile=Join-Path $moduleRoot $relative
                if(-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)){continue}
                $target=Join-Path (Join-Path $destination $module) $relative
                New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
                Copy-Item -LiteralPath $sourceFile -Destination $target
            }
        }
    }
    # Imported manual fixtures can contain live credentials. Refuse to prepare
    # or commit an import until its source values have been externalized.
    & python (Join-Path $PSScriptRoot 'check-source-secrets.py')
    if($LASTEXITCODE -ne 0){throw 'Imported credentials detected. Externalize them before continuing; values were not printed.'}
    $receipt.revision=$revision
    $receipt | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $destination 'upstream-provenance.json')
    # Generate packaged provenance only after updating all source revision receipts.
    & python (Join-Path $PSScriptRoot $(if($App -eq 'Flow'){'prepare-flow.py'}else{'prepare-smarttube.py'}))
    if($LASTEXITCODE -ne 0){throw 'Integration adaptation failed'}
    Write-Output 'Source imported. Review the source diff and any dependency/schema changes before building. Nothing was installed or committed.'
} finally {
    if(Test-Path -LiteralPath $checkout) {
        $resolved=(Resolve-Path -LiteralPath $checkout).Path
        $allowed=(Resolve-Path -LiteralPath $importRoot).Path + [IO.Path]::DirectorySeparatorChar
        if(-not $resolved.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe cleanup path'}
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
