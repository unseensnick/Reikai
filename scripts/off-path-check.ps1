<#
.SYNOPSIS
    Fail loudly if a Mihon (or tsundoku) file Reikai has deleted changed upstream.

.DESCRIPTION
    Reikai deletes upstream UI files that a Reikai-owned twin fully replaced (see
    docs/dev/off-path-manifest.md). A deleted file leaves nothing for the next hand-sync to diff, so a
    buried upstream change could silently do nothing. This reads the manifest and, for each listed path,
    diffs it across a sync range in the matching refs/ clone. Any path that changed (or vanished) is
    reported with its replacement, and the script exits non-zero.

    Run it as a step of a Mihon sync (see docs/dev/upstream-sync.md "How to sync"), passing the base each
    upstream is currently synced through (the top ledger row). tsundoku is skipped until its clone exists.

.EXAMPLE
    pwsh scripts/off-path-check.ps1 -MihonBase 03229a380
#>
param(
    [Parameter(Mandatory = $true)][string]$MihonBase,
    [string]$TsundokuBase,
    [string]$RefsRoot
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $RefsRoot) { $RefsRoot = Join-Path (Split-Path $repoRoot -Parent) 'refs' }
$manifestPath = Join-Path $repoRoot 'docs/dev/off-path-manifest.md'

if (-not (Test-Path $manifestPath)) { throw "manifest not found: $manifestPath" }

# Parse the manifest table: data rows look like `| <path> | <upstream> | <replacement> |`.
$entries = Get-Content $manifestPath |
    Where-Object { $_ -match '^\|\s*app/' } |
    ForEach-Object {
        $cols = ($_ -split '\|') | ForEach-Object { $_.Trim() }
        [pscustomobject]@{ Path = $cols[1]; Upstream = $cols[2]; Replacement = $cols[3] }
    }

if (-not $entries) { Write-Host 'off-path check: manifest has no entries.'; exit 0 }

$bases = @{ mihon = $MihonBase; tsundoku = $TsundokuBase }
$changed = @()

foreach ($group in $entries | Group-Object Upstream) {
    $upstream = $group.Name
    $clone = Join-Path $RefsRoot $upstream
    $base = $bases[$upstream]

    if (-not (Test-Path $clone)) { Write-Host "skip ${upstream}: no clone at $clone"; continue }
    if (-not $base) { Write-Host "skip ${upstream}: no base SHA passed"; continue }

    foreach ($e in $group.Group) {
        git -C $clone cat-file -e "HEAD:$($e.Path)" 2>$null
        if ($LASTEXITCODE -ne 0) {
            $changed += "  VANISHED $($e.Path)`n           -> reconcile into $($e.Replacement)"
            continue
        }
        $diff = git -C $clone diff --name-only "$base..HEAD" -- $e.Path
        if ($diff) {
            $changed += "  CHANGED  $($e.Path)`n           -> reconcile into $($e.Replacement)"
        }
    }
}

if ($changed.Count -gt 0) {
    Write-Host ''
    Write-Host 'Off-path files changed upstream in this range:'
    $changed | ForEach-Object { Write-Host $_ }
    Write-Host ''
    Write-Host 'Reconcile each upstream change into its replacement above, then re-run.'
    exit 1
}

Write-Host 'off-path check clean: no manifested file changed upstream in range.'
