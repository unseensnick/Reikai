<#
.SYNOPSIS
    Which code is Mihon's and which is Reikai's: where the Mihon clone lives, which upstream commit
    Reikai is synced through, and which lines of a Mihon file Reikai fenced as its own. Dot-sourced by
    dup-check.ps1 and rk-fence-report.ps1, so the two read these rules from one place.
#>

# refs/ sits beside the main worktree, and a linked worktree's own root is somewhere else.
function Get-DefaultRefsRoot([string]$RepoRoot) {
    $mainWorktree = Split-Path (git -C $RepoRoot rev-parse --path-format=absolute --git-common-dir) -Parent
    Join-Path (Split-Path $mainWorktree -Parent) 'refs'
}

# The synced base is the Base cell of the top (newest) row of the ledger in docs/dev/upstream-sync.md,
# so the hook, CI and a sync all read one tracked value. A row that keeps the base still names it.
function Get-SyncedMihonBase([string]$RepoRoot) {
    $inLedger = $false
    $pastHeader = $false
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $RepoRoot 'docs/dev/upstream-sync.md'))) {
        if ($line.StartsWith('## ')) {
            $inLedger = $line.TrimEnd() -ceq '## Synced-base ledger'
            continue
        }
        if (-not $inLedger) { continue }
        if ($line -match '^\|(\s*-+\s*\|)+\s*$') { $pastHeader = $true; continue }
        if ($pastHeader) {
            if ($line -cmatch '^\|\s*`([0-9a-f]{7,40})`') { return $Matches[1] }
            break
        }
    }
    throw 'docs/dev/upstream-sync.md: the top row of the Synced-base ledger names no base SHA.'
}

# A marker is RK after a comment opener. A backtick before the opener is prose naming the convention.
$RkIslandOpen = '(?<!`)(//|#|--|<!--)\s*RK\s*-->'
$RkIslandClose = '(?<!`)(//|#|--|<!--)\s*RK\s*<--'
# A whole-file rewrite carries this header instead of islands, and is hand-merged whole at a sync.
$RkWholeFileHeader = '^\s*//\s*RK:\s*whole file\b'

function Test-RkWholeFile([string[]]$Lines) {
    @($Lines | Select-Object -First 5 | Where-Object { $_ -cmatch $RkWholeFileHeader }).Count -gt 0
}

# Mask is 1-based: Mask[n] is true for line n inside an island, both marker lines included. Error names
# the first marker that opens inside an open island, closes none, or is never closed.
function Get-RkIslands([string[]]$Lines) {
    $mask = New-Object bool[] ($Lines.Count + 2)
    $openedAt = 0
    $err = $null
    for ($n = 1; $n -le $Lines.Count; $n++) {
        $text = $Lines[$n - 1]
        if ($text -cmatch $RkIslandOpen) {
            if ($openedAt -and -not $err) { $err = "line ${n} opens an RK island inside the one opened at line $openedAt" }
            $openedAt = $n
        }
        $mask[$n] = $openedAt -gt 0
        if ($text -cmatch $RkIslandClose) {
            if (-not $openedAt -and -not $err) { $err = "line ${n} closes an RK island that was never opened" }
            $openedAt = 0
        }
    }
    if ($openedAt -and -not $err) { $err = "line $openedAt opens an RK island that is never closed" }
    [pscustomobject]@{ Mask = $mask; Error = $err }
}
