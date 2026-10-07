<#
.SYNOPSIS
    Checks that scripts/dup-check.ps1 reads Reikai's code where it should and Mihon's nowhere else.

.DESCRIPTION
    Each case builds a small Reikai repository and runs dup-check against a two-commit Mihon fixture:
    the synced base has Renamed.kt, which upstream drops after it, and HEAD has Ahead.kt, which Reikai
    ported ahead of the base. Shared.kt is at both. A rule that silently stops matching looks exactly
    like a clean tree, so every rule has a case that must fail as well as one that must pass.

    Run it after touching dup-check.ps1 or mihon-ownership.ps1: pwsh scripts/dup-check-test.ps1
#>
$ErrorActionPreference = 'Stop'
$dupCheck = Join-Path $PSScriptRoot 'dup-check.ps1'
$work = Join-Path ([IO.Path]::GetTempPath()) ("dup-check-test-" + [guid]::NewGuid().ToString('N'))
$pass = 0
$broke = 0

function Write-Text([string]$path, [string]$text) {
    New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
    [IO.File]::WriteAllText($path, $text)
}

function Invoke-Git([string]$dir) {
    git -C $dir -c user.name=test -c user.email=test@example.com -c commit.gpgsign=false -c core.autocrlf=false @args | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "git $args failed in $dir" }
}

New-Item -ItemType Directory -Path $work | Out-Null
$mihon = Join-Path $work 'mihon'
$m = 'app/src/main/java/eu/kanade/m'
Invoke-Git $work init -q mihon
foreach ($name in 'Renamed', 'Shared') { Write-Text (Join-Path $mihon "$m/$name.kt") "package m`n" }
Invoke-Git $mihon add -A
Invoke-Git $mihon commit -q -m base
$base = (git -C $mihon rev-parse --short=9 HEAD).Trim()
Remove-Item (Join-Path $mihon "$m/Renamed.kt")
Write-Text (Join-Path $mihon "$m/Ahead.kt") "package m`n"
Invoke-Git $mihon add -A
Invoke-Git $mihon commit -q -m head

# 150-odd tokens once normalised, past the 100-token minimum.
$clone = @'
fun clone(a: Int, b: Int): Int {
    var total = 0
    val items = listOf(a, b, a + b, a - b, a * b)
    for (item in items) {
        if (item > 10) {
            total += item * 2
        } else if (item < 0) {
            total -= item / 3
        } else {
            total = total + item - 1
        }
    }
    while (total > 1000) {
        total = total / 2 + a
    }
    return when {
        total > 100 -> total / 2
        total < -100 -> total * 2
        else -> total + a + b
    }
}
'@
$owned = "package reikai`n`n$clone"
$ledgerTop = $base

# check <name> <expected exit> <text the output must carry, or ''> <files: path -> text> [clone path]
# [-InHook] [-Staged path: commit everything else first, then run the hook's -Staged mode]
function Test-Case([string]$name, [int]$want, [string]$says, [hashtable]$files, [string]$clonePath = $mihon, [switch]$InHook, [string]$Staged) {
    $repo = Join-Path $work ("case" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    # The stale second row is there so a parser that reads any row but the top one fails every case.
    Write-Text (Join-Path $repo 'docs/dev/upstream-sync.md') (
        "# Sync`n`n## Synced-base ledger`n`n| Base (mihon) | Reikai |`n|---|---|`n" +
        "| ``$ledgerTop`` (unchanged) | x |`n| ``0123456`` | y |`n`n## After`n")
    Write-Text (Join-Path $repo 'scripts/dup-baseline.txt') "# empty`n"
    # dup-check refuses a scope under 300 Reikai-owned files, as a stale pattern would give.
    for ($i = 0; $i -lt 300; $i++) { Write-Text (Join-Path $repo "app/src/main/java/reikai/fill/F$i.kt") "package fill`nval v$i = $i`n" }
    foreach ($path in $files.Keys) { Write-Text (Join-Path $repo $path) $files[$path] }
    Invoke-Git $work init -q (Split-Path $repo -Leaf)
    $mode = @()
    if ($Staged) {
        Invoke-Git $repo add -A -- . ":(exclude)$Staged"
        Invoke-Git $repo commit -q -m before
        $mode = @('-Staged')
    }
    Invoke-Git $repo add -A

    # A pre-commit hook runs with the commit's GIT_DIR exported.
    if ($InHook) { $env:GIT_DIR = Join-Path $repo '.git' }
    try {
        $out = (pwsh -NoProfile -File $dupCheck -RepoRoot $repo -MihonClone $clonePath @mode *>&1 | Out-String)
        $got = $LASTEXITCODE
    } finally {
        Remove-Item Env:GIT_DIR -ErrorAction SilentlyContinue
    }
    if ($got -eq $want -and (-not $says -or $out.Contains($says))) {
        Write-Host "  ok    $name"
        $script:pass++
    } else {
        Write-Host "  BROKE $name (wanted exit $want$(if ($says) { " saying '$says'" }), got $got)"
        Write-Host ($out.Trim() -replace '(?m)^', '        ')
        $script:broke++
    }
}

$reikaiA = 'app/src/main/java/reikai/A.kt'
try {
    Write-Host 'ownership'
    Test-Case 'reports a clone between two Reikai files' 1 '1 new cross-file clone' @{ $reikaiA = $owned; 'app/src/main/java/reikai/B.kt' = $owned }
    Test-Case 'reads a file Mihon has only at the synced base as Mihon''s' 0 '' @{ $reikaiA = $owned; "$m/Renamed.kt" = "package m`n`n$clone" }
    Test-Case 'reads a file Mihon has only at its HEAD as Mihon''s' 0 '' @{ $reikaiA = $owned; "$m/Ahead.kt" = "package m`n`n$clone" }

    Write-Host 'RK islands'
    Test-Case 'reads code inside an island as Reikai''s' 1 "$m/Shared.kt" @{ $reikaiA = $owned; "$m/Shared.kt" = "package m`n`n// RK --> ours`n$clone// RK <--`n" }
    Test-Case 'ignores code outside the islands' 0 '' @{ $reikaiA = $owned; "$m/Shared.kt" = "package m`n`n$clone`n// RK --> ours`nval x = 1`n// RK <--`n" }
    Test-Case 'reads a whole-file rewrite as Reikai''s' 1 "$m/Shared.kt" @{ $reikaiA = $owned; "$m/Shared.kt" = "// RK: whole file, a test`npackage m`n`n$clone" }
    Test-Case 'fails an island closed but never opened' 1 'never opened' @{ "$m/Shared.kt" = "package m`nval y = 2`n// RK <--`n" }
    Test-Case 'fails an island opened but never closed' 1 'never closed' @{ "$m/Shared.kt" = "package m`n// RK --> ours`nval y = 2`n" }
    Test-Case 'fails an island opened inside another' 1 'inside the one opened' @{ "$m/Shared.kt" = "package m`n// RK -->`n// RK -->`nval y = 2`n// RK <--`n" }
    Test-Case 'fails a broken island the commit stages' 1 'never opened' @{ "$m/Shared.kt" = "package m`n// RK <--`n" } -Staged "$m/Shared.kt"
    Test-Case 'leaves a broken island the commit does not stage' 0 '' @{ "$m/Shared.kt" = "package m`n// RK <--`n"; 'app/src/main/java/reikai/B.kt' = "package reikai`nval b = 1`n" } -Staged 'app/src/main/java/reikai/B.kt'

    Write-Host 'the Mihon clone'
    Test-Case 'reads the clone, not the repository a hook names' 0 '' @{ $reikaiA = $owned; "$m/Ahead.kt" = "package m`n`n$clone" } -InHook
    Test-Case 'fails without a Mihon clone' 1 'no Mihon clone' @{ $reikaiA = $owned } (Join-Path $work 'absent')
    $ledgerTop = '0000000'
    Test-Case 'fails when the clone lacks the synced base' 1 'has no commit 0000000' @{ $reikaiA = $owned }
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "passed=$pass broken=$broke"
if ($broke -ne 0) { exit 1 }
