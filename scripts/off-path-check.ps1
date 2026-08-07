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

    A pass that stops below upstream HEAD, because a commit in the middle is deferred, passes -MihonThrough
    to bound the range at the point it actually reaches. The stamp then records that point instead of HEAD,
    and the commit-msg hook makes the message cite it.

    Reporting a path is not the same as leaving it unhandled: the diff is upstream-side, so a path stays
    reported however well its twin was reconciled, and the range only reads clean once the base advances
    past it. -Reconciled is how a pass says it did the hand-merge, so it can stamp and commit; it is an
    assertion by the operator, so use it only after actually walking each reported path.

.EXAMPLE
    pwsh scripts/off-path-check.ps1 -MihonBase 03229a380

.EXAMPLE
    pwsh scripts/off-path-check.ps1 -MihonBase 45b1e781e -MihonThrough 0648e2eaa -Reconciled
#>
param(
    [Parameter(Mandatory = $true)][string]$MihonBase,
    [string]$MihonThrough = 'HEAD',
    [string]$TsundokuBase,
    [string]$RefsRoot,
    [switch]$Reconciled
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $RefsRoot) { $RefsRoot = Join-Path (Split-Path $repoRoot -Parent) 'refs' }
$manifestPath = Join-Path $repoRoot 'docs/dev/off-path-manifest.md'

if (-not (Test-Path $manifestPath)) { throw "manifest not found: $manifestPath" }

# Parse the manifest table: data rows look like `| <path> | <upstream> | <replacement> |`.
$entries = Get-Content $manifestPath |
    Where-Object { $_ -match '^\|\s*[a-z0-9-]+/' } |
    ForEach-Object {
        $cols = ($_ -split '\|') | ForEach-Object { $_.Trim() }
        [pscustomobject]@{ Path = $cols[1]; Upstream = $cols[2]; Replacement = $cols[3] }
    }

if (-not $entries) { Write-Host 'off-path check: manifest has no entries.'; exit 0 }

$bases = @{ mihon = $MihonBase; tsundoku = $TsundokuBase }
$throughs = @{ mihon = $MihonThrough; tsundoku = 'HEAD' }
$changed = @()

foreach ($group in $entries | Group-Object Upstream) {
    $upstream = $group.Name
    $clone = Join-Path $RefsRoot $upstream
    $base = $bases[$upstream]
    $through = $throughs[$upstream]

    if (-not (Test-Path $clone)) { Write-Host "skip ${upstream}: no clone at $clone"; continue }
    if (-not $base) { Write-Host "skip ${upstream}: no base SHA passed"; continue }

    foreach ($e in $group.Group) {
        git -C $clone cat-file -e "${through}:$($e.Path)" 2>$null
        if ($LASTEXITCODE -ne 0) {
            $changed += "  VANISHED $($e.Path)`n           -> reconcile into $($e.Replacement)"
            continue
        }
        $diff = git -C $clone diff --name-only "$base..$through" -- $e.Path
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
    if (-not $Reconciled) {
        Write-Host 'Reconcile each upstream change into its replacement above, then re-run with -Reconciled.'
        exit 1
    }
    Write-Host 'Acknowledged as reconciled by hand (-Reconciled).'
}

# Stamp the run so the commit-msg hook can tell a sync commit that the check actually ran, and how far
# up the upstream history it reached. Without this the check is opt-in and a forgotten step is
# indistinguishable from a clean one.
$stampDir = Join-Path $repoRoot '.git'
$mihonThroughSha = (git -C (Join-Path $RefsRoot 'mihon') rev-parse $MihonThrough 2>$null)
if ((Test-Path $stampDir) -and $LASTEXITCODE -eq 0 -and $mihonThroughSha) {
    Set-Content -Path (Join-Path $stampDir 'off-path-checked') -Value $mihonThroughSha.Trim() -NoNewline
}

if ($changed.Count -gt 0) {
    Write-Host "off-path check stamped through ${MihonThrough}: $($changed.Count) reported path(s) reconciled by hand."
} else {
    Write-Host "off-path check clean: no manifested file changed upstream through $MihonThrough."
}
