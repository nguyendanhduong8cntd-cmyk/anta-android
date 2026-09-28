param(
    [Parameter(Mandatory = $true)]
    [string]$XmlPath
)

$ErrorActionPreference = "Stop"
[xml]$doc = Get-Content -LiteralPath $XmlPath -Raw -Encoding UTF8
$doc.SelectNodes("//node") | ForEach-Object {
    $a = $_.Attributes
    [PSCustomObject]@{
        class = $a["class"].Value
        text = $a["text"].Value
        desc = $a["content-desc"].Value
        id = $a["resource-id"].Value
        clickable = $a["clickable"].Value
        bounds = $a["bounds"].Value
    }
} | Format-Table -AutoSize
