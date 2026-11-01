# Reads docs/dev/live-twins.md for off-path-check.ps1. Dot-source it; it defines functions and runs nothing.

function Get-LiveTwins([string]$tablePath) {
    Get-Content $tablePath |
        Where-Object { $_ -match '^\|\s*[a-z0-9-]+/' } |
        ForEach-Object {
            $cols = ($_ -split '\|') | ForEach-Object { $_.Trim() }
            [pscustomobject]@{ Mihon = $cols[1]; Twin = $cols[2]; Pin = $cols[3] }
        }
}

# One line per row whose Mihon file changed in base..through, naming the twin to carry the change to.
function Get-LiveTwinChanges([string]$clone, [string]$base, [string]$through, [object[]]$twins) {
    foreach ($t in $twins) {
        if (git -C $clone diff --name-only "$base..$through" -- $t.Mihon) {
            "  $($t.Mihon)`n           -> take it to $($t.Twin) (pin: $($t.Pin))"
        }
    }
}

# Rows naming a file that is gone from this tree, which would silently stop alarming.
function Get-StaleLiveTwins([string]$repoRoot, [object[]]$twins) {
    foreach ($t in $twins) {
        foreach ($path in $t.Mihon, $t.Twin) {
            if (-not (Test-Path (Join-Path $repoRoot $path))) { $path }
        }
    }
}
