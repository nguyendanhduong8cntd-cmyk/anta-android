param(
    [string]$Name = "screen"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root "docs\clone-capture"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$remotePng = "/sdcard/clone_capture.png"
$remoteXml = "/sdcard/clone_window.xml"
$pngPath = Join-Path $outDir "$Name.png"
$xmlPath = Join-Path $outDir "$Name.xml"

Remove-Item -LiteralPath $pngPath, $xmlPath -Force -ErrorAction SilentlyContinue
adb shell rm -f $remotePng $remoteXml | Out-Null
adb shell screencap -p $remotePng | Out-Null
adb pull $remotePng $pngPath | Out-Null

$dump = adb shell uiautomator dump $remoteXml 2>&1
$remoteExists = adb shell "test -s $remoteXml && echo yes"
if ($remoteExists -match "yes") {
    adb pull $remoteXml $xmlPath | Out-Null
} else {
    Write-Warning "UI hierarchy unavailable: $dump"
}

Write-Output $pngPath
if (Test-Path -LiteralPath $xmlPath) { Write-Output $xmlPath }
