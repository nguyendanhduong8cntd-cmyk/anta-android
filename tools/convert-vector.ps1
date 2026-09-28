param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [Parameter(Mandatory = $true)]
    [string]$ResourcePath
)

$ErrorActionPreference = "Stop"
$aapt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "aapt.exe" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1

if (-not $aapt) { throw "aapt.exe not found" }

# This is the lossless source for reconstructing vectors. Keep both fill and
# stroke attributes; the final hex value on each line is the resolved value.
& $aapt dump xmltree $ApkPath $ResourcePath |
    Select-String -Pattern "vector|path|viewport|width|height|pathData|fillColor|strokeColor|strokeWidth|strokeLineCap|strokeLineJoin|fillAlpha|strokeAlpha"
