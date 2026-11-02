# Reads the path-keyed rows of a markdown table for off-path-check.ps1 and live-twins.ps1. Dot-source it.

# A table with no rows throws: a format that stopped matching would otherwise read as a clean run.
function Get-PathTableRows([string]$tablePath) {
    $rows = @(Get-Content $tablePath | Where-Object { $_ -cmatch '^\|\s*[a-z0-9-]+/' })
    if (-not $rows) { throw "no table rows read from $tablePath" }
    $rows | ForEach-Object { , @(($_ -split '\|') | ForEach-Object { $_.Trim() }) }
}
