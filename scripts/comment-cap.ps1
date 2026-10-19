<#
.SYNOPSIS
    Fails a run of more than 8 consecutive comment lines in Reikai's own code.

.DESCRIPTION
    See .claude/rules/code-quality.md "The length cap". Reikai's code is every .kt, .kts, .sq and .sqm
    file Mihon does not have, read whole, plus the RK islands of the ones it has; Mihon's own comments
    keep upstream shape and are never read. Ownership comes from mihon-ownership.ps1, as dup-check.ps1
    reads it, so a missing Mihon clone fails the check.

    A KDoc tag list (@param, @return, ...) stops the count for the rest of its block, since tags
    enumerate the signature rather than narrate. An island's own marker lines are not counted.

    Modes: the whole working tree (CI), and -Staged (pre-commit: each staged file as staged, whole,
    since a block added over two commits is still one block).
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$MihonClone,
    [switch]$Staged
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mihon-ownership.ps1')
$Cap = 8

if (-not $MihonClone) { $MihonClone = Join-Path (Get-DefaultRefsRoot $RepoRoot) 'mihon' }
if (-not (Test-Path -LiteralPath $MihonClone)) {
    Write-Host "comment-cap: no Mihon clone at $MihonClone, which says which files are Reikai's. Clone mihonapp/mihon there (--filter=blob:none is enough) or pass -MihonClone."
    exit 1
}
try {
    $mihonPaths = Get-MihonPaths $RepoRoot $MihonClone
} catch {
    Write-Host "comment-cap: $($_.Exception.Message)"
    exit 1
}

$globs = '*.kt', '*.kts', '*.sq', '*.sqm'
if ($Staged) {
    $paths = @(git -C $RepoRoot diff --cached --name-only --diff-filter=ACMR -- @globs)
} else {
    $paths = @(git -C $RepoRoot ls-files -- @globs)
}
if ($LASTEXITCODE -ne 0) {
    Write-Error 'comment-cap: git could not list the files.'
    exit 1
}

# Returns "path:line: N lines" for each run past the cap among the lines Counted marks (1-based).
function Find-LongRuns([string]$path, [string[]]$lines, [bool[]]$counted) {
    $sqlComments = $path -match '\.sqm?$'
    $inBlock = $false
    $tagged = $false
    $run = 0
    $start = 0
    for ($n = 1; $n -le $lines.Count + 1; $n++) {
        $isComment = $false
        if ($n -le $lines.Count) {
            $line = $lines[$n - 1].TrimStart()
            if ($inBlock) { $isComment = $true; if ($line.Contains('*/')) { $inBlock = $false } }
            elseif ($line.StartsWith('/*')) { $isComment = $true; $inBlock = -not $line.Contains('*/'); $tagged = $false }
            elseif ($line.StartsWith('//')) { $isComment = $true }
            elseif ($sqlComments -and $line.StartsWith('--')) { $isComment = $true }
            if ($isComment -and $line -match '^\*\s*@') { $tagged = $true }
        }
        if ($isComment -and -not $tagged -and $counted[$n]) {
            if ($run -eq 0) { $start = $n }
            $run++
        } else {
            if ($run -gt $Cap) { "${path}:${start}: $run lines" }
            $run = 0
        }
        if (-not $isComment) { $tagged = $false }
    }
}

$found = [System.Collections.Generic.List[string]]::new()
$owned = 0
foreach ($path in $paths) {
    $text = if ($Staged) { (git -C $RepoRoot show ":$path") -join "`n" } else { [IO.File]::ReadAllText((Join-Path $RepoRoot $path)) }
    $lines = $text -split "`r?`n"
    $counted = New-Object bool[] ($lines.Count + 2)
    if (-not $mihonPaths.Contains($path) -or (Test-RkWholeFile $lines)) {
        $owned++
        for ($n = 1; $n -le $lines.Count; $n++) { $counted[$n] = $true }
    } elseif ($text.Contains('RK')) {
        # dup-check reports a broken island; here it only bounds what is read.
        $mask = (Get-RkIslands $lines).Mask
        for ($n = 1; $n -le $lines.Count; $n++) {
            $marker = $lines[$n - 1] -cmatch $RkIslandOpen -or $lines[$n - 1] -cmatch $RkIslandClose
            $counted[$n] = $mask[$n] -and -not $marker
        }
    } else {
        continue
    }
    foreach ($hit in Find-LongRuns $path $lines $counted) { $found.Add($hit) }
}

# A glob that stops matching, or an ownership read that claims everything for Mihon, reads as clean.
if (-not $Staged -and $owned -lt 300) {
    Write-Error "comment-cap: only $owned Reikai-owned files in scope. The globs or the Mihon clone are stale."
    exit 1
}
if ($found.Count -gt 0) {
    Write-Host "code comments: a comment block runs past the cap of $Cap lines."
    Write-Host 'Keep the invariant, the trap or the coupling in the code as a sentence or two; move the'
    Write-Host "narrative to the area's doc in docs/dev/subsystems/ and point at it by filename:"
    $found | ForEach-Object { Write-Host $_ }
    exit 1
}
