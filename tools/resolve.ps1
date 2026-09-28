param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$Pattern = ""
)

$ErrorActionPreference = "Stop"
$aapt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "aapt.exe" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1

if (-not $aapt) { throw "aapt.exe not found" }

$lines = & $aapt dump --values resources $ApkPath
if ($Pattern) {
    $lines | Select-String -Pattern $Pattern -Context 2,6
} else {
    $lines
}
