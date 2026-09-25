<#
.SYNOPSIS
    List Reikai edits to Mihon files that carry no // RK marker, and RK markers in Reikai-owned files.

.DESCRIPTION
    Reikai fences every edit to a Mihon file with an RK marker (see .claude/rules/architecture.md), so a
    hand-sync can find each divergence by grep. Nothing checks that at commit time, and an unfenced edit
    is only found when a sync trips over it. This diffs every tracked file that also exists in refs/mihon
    at the synced base against that base, whitespace ignored, and reports each changed hunk that has no
    marker covering it. It also reports RK markers in files refs/mihon does not have, where everything
    is Reikai's and the marker says nothing.

    A hunk counts as fenced when each changed line (imports and blank lines exempt) sits inside a
    // RK --> ... // RK <-- island, carries an RK marker itself, follows an RK note earlier in the same
    run of changed lines, or sits right under an RK note (blank lines between allowed). A hunk of five
    lines or fewer with a marker anywhere in it is fenced whole, and a fenced hunk's note carries over a
    gap of two unchanged lines. A removal counts as fenced when an RK note stands within two lines of
    where the removed lines were. These are heuristics: read a reported hunk before fencing it. A file
    whose first lines carry a `// RK: whole file` header is a recorded rewrite, listed but not hunk-checked.

    A Reikai-owned file is one refs/mihon does not have at that path, so a file Reikai renamed from a
    Mihon one reports its markers as strays; judge those by hand.

    It is a report for a sync or an audit, not a gate, and is wired into no hook. It exits 1 when it
    reports anything, so a caller can still branch on it.

.EXAMPLE
    pwsh scripts/rk-fence-report.ps1 -MihonBase f52d890e7
#>
param(
    [Parameter(Mandatory = $true)][string]$MihonBase,
    [string]$RefsRoot,
    [string[]]$Extensions = @('.kt', '.kts', '.sq', '.sqm', '.pro')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $RefsRoot) { $RefsRoot = Join-Path (Split-Path $repoRoot -Parent) 'refs' }
$mihon = Join-Path $RefsRoot 'mihon'
if (-not (Test-Path $mihon)) { throw "no Mihon clone at $mihon" }

git -C $mihon rev-parse --verify --quiet "$MihonBase^{commit}" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "refs/mihon has no commit $MihonBase" }

# A marker is RK after a comment opener. A backtick before the opener is prose naming the convention.
$marker = '(?<!`)(//|/\*|\*|#|--|<!--)\s*RK\b'
$islandOpen = '(?<!`)(//|#|--|<!--)\s*RK\s*-->'
$islandClose = '(?<!`)(//|#|--|<!--)\s*RK\s*<--'
$exempt = '^\s*$|^\s*import\s'
# A whole-file rewrite carries this header instead of islands, and is hand-merged whole at a sync.
$wholeFileHeader = '^\s*//\s*RK:\s*whole file\b'

function Test-Wanted([string]$path) { $Extensions -contains [IO.Path]::GetExtension($path) }

$upstream = @{}
foreach ($row in git -C $mihon ls-tree -r $MihonBase) {
    $meta, $path = $row -split "`t", 2
    if (Test-Wanted $path) { $upstream[$path] = ($meta -split ' ')[2] }
}

$local = @(git -C $repoRoot ls-files | Where-Object { (Test-Wanted $_) -and (Test-Path (Join-Path $repoRoot $_)) })
$shared = @($local | Where-Object { $upstream.ContainsKey($_) })
$owned = @($local | Where-Object { -not $upstream.ContainsKey($_) })

# Hash the working tree in one process, so only files that really differ are diffed.
$hashes = @($shared | git -C $repoRoot hash-object --stdin-paths)
$differing = for ($i = 0; $i -lt $shared.Count; $i++) {
    if ($hashes[$i] -ne $upstream[$shared[$i]]) { $shared[$i] }
}

$tmp = [IO.Path]::GetTempFileName()
$unfenced = [ordered]@{}
$rewrites = New-Object System.Collections.Generic.List[string]
try {
    foreach ($path in $differing) {
        $full = Join-Path $repoRoot $path
        $lines = @(Get-Content -LiteralPath $full)
        if (@($lines | Select-Object -First 5 | Where-Object { $_ -cmatch $wholeFileHeader }).Count -gt 0) {
            $rewrites.Add($path)
            continue
        }
        # 1-based: island[n] is true for line n inside an island, both marker lines included.
        $island = New-Object bool[] ($lines.Count + 2)
        $open = $false
        for ($n = 1; $n -le $lines.Count; $n++) {
            $text = $lines[$n - 1]
            if ($text -cmatch $islandOpen) { $open = $true }
            $island[$n] = $open
            if ($text -cmatch $islandClose) { $open = $false }
        }
        function Test-Marker([int]$n) { $n -ge 1 -and $n -le $lines.Count -and $lines[$n - 1] -cmatch $marker }

        # Written with LF endings, so git does not warn about converting the copy.
        [IO.File]::WriteAllText($tmp, ((git -C $mihon cat-file blob $upstream[$path]) -join "`n") + "`n")
        $diff = git diff --no-index --no-color -U0 -w --ignore-blank-lines -- $tmp $full

        $hunks = @()
        foreach ($d in $diff) {
            if ($d -match '^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@') {
                $hunks += [pscustomobject]@{
                    Start = [int]$Matches[2]
                    Added = if ($null -ne $Matches[3]) { [int]$Matches[3] } else { 1 }
                    Removed = New-Object System.Collections.Generic.List[string]
                }
            } elseif ($hunks -and $d -match '^-') {
                $hunks[-1].Removed.Add($d.Substring(1))
            }
        }

        $coveredUpTo = -10
        foreach ($h in $hunks) {
            $bad = $null
            # A note carries over a gap of up to two unchanged lines, such as a block's closing brace.
            $carried = $h.Start - $coveredUpTo -le 3
            if ($h.Added -eq 0) {
                $significant = @($h.Removed | Where-Object { $_ -notmatch $exempt })
                $noted = $carried -or $island[[Math]::Max($h.Start, 1)] -or
                    @(($h.Start - 1)..($h.Start + 2) | Where-Object { Test-Marker $_ }).Count -gt 0
                if (-not $significant) { continue }
                if ($noted) { $coveredUpTo = $h.Start; continue }
                $bad = "after L$($h.Start)  removed $($significant.Count) line(s): $($significant[0].Trim())"
            } else {
                $end = $h.Start + $h.Added - 1
                # An RK note directly above the hunk, blank lines between allowed, covers it.
                $above = $h.Start - 1
                while ($above -ge 1 -and $lines[$above - 1] -match '^\s*$') { $above-- }
                $covered = $carried -or (Test-Marker $above)
                # A marker anywhere in a hunk this short labels all of it, as a trailing note does.
                if ($h.Added -le 5 -and @($h.Start..$end | Where-Object { Test-Marker $_ }).Count -gt 0) { $covered = $true }
                $fenced = $false
                for ($n = $h.Start; $n -le $end; $n++) {
                    $text = $lines[$n - 1]
                    if (Test-Marker $n) { $covered = $true; $fenced = $true; continue }
                    if ($text -match $exempt) { continue }
                    if ($island[$n] -or $covered) { $fenced = $true; continue }
                    $bad = "L$($h.Start)-$end  $($text.Trim())"
                    break
                }
                # Only a hunk a marker fenced carries over; an imports-only hunk has nothing to carry.
                if (-not $bad -and $fenced) { $coveredUpTo = $end }
            }
            if ($bad) {
                if (-not $unfenced.Contains($path)) { $unfenced[$path] = New-Object System.Collections.Generic.List[string] }
                $unfenced[$path].Add($bad)
            }
        }
    }
} finally {
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
}

$strays = foreach ($path in $owned) {
    $n = 0
    foreach ($text in Get-Content -LiteralPath (Join-Path $repoRoot $path)) {
        $n++
        if ($text -cmatch $marker) { "  ${path}:$n  $($text.Trim())" }
    }
}

$hunkCount = ($unfenced.Values | ForEach-Object { $_.Count } | Measure-Object -Sum).Sum
Write-Host "rk-fence report against refs/mihon $MihonBase ($($differing.Count) of $($shared.Count) shared files differ)"
Write-Host ''
Write-Host "Unfenced edits in Mihon files: $([int]$hunkCount) hunk(s) in $($unfenced.Count) file(s)"
foreach ($path in $unfenced.Keys) {
    Write-Host "  $path"
    foreach ($line in $unfenced[$path]) { Write-Host "    $line" }
}
Write-Host ''
Write-Host "Whole-file rewrites, hand-merged whole (not hunk-checked): $($rewrites.Count)"
$rewrites | ForEach-Object { Write-Host "  $_" }
Write-Host ''
Write-Host "RK markers in Reikai-owned files: $(@($strays).Count)"
$strays | ForEach-Object { Write-Host $_ }

if ($hunkCount -or $strays) { exit 1 }
exit 0
