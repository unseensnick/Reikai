<#
.SYNOPSIS
    Checks that scripts/comment-cap.ps1 measures Reikai's comments and leaves Mihon's alone.

.DESCRIPTION
    Each case builds a small Reikai repository and runs comment-cap against a Mihon fixture that has
    Shared.kt and app/build.gradle.kts. A rule that silently stops matching looks exactly like a clean
    tree, so every rule has a case that must fail as well as one that must pass.

    Run it after touching comment-cap.ps1 or mihon-ownership.ps1: pwsh scripts/comment-cap-test.ps1
#>
$ErrorActionPreference = 'Stop'
$commentCap = Join-Path $PSScriptRoot 'comment-cap.ps1'
$work = Join-Path ([IO.Path]::GetTempPath()) ("comment-cap-test-" + [guid]::NewGuid().ToString('N'))
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
Write-Text (Join-Path $mihon "$m/Shared.kt") "package m`n"
Write-Text (Join-Path $mihon 'app/build.gradle.kts') "plugins {}`n"
Invoke-Git $mihon add -A
Invoke-Git $mihon commit -q -m base
$base = (git -C $mihon rev-parse --short=9 HEAD).Trim()

function Get-Lines([string]$prefix, [int]$count) { ((1..$count) | ForEach-Object { "$prefix line $_" }) -join "`n" }
$nine = (Get-Lines '//' 9) + "`n"
$eight = (Get-Lines '//' 8) + "`n"

# check <name> <expected exit> <text the output must carry, or ''> <files: path -> text> [clone path]
# [-Staged path: commit everything else first, then run the hook's -Staged mode]
function Test-Case([string]$name, [int]$want, [string]$says, [hashtable]$files, [string]$clonePath = $mihon, [string]$Staged) {
    $repo = Join-Path $work ("case" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    Write-Text (Join-Path $repo 'docs/dev/upstream-sync.md') (
        "# Sync`n`n## Synced-base ledger`n`n| Base (mihon) | Reikai |`n|---|---|`n| ``$base`` | x |`n")
    # The tree mode refuses a scope under 300 Reikai-owned files, as a stale glob would give.
    for ($i = 0; $i -lt 300; $i++) { Write-Text (Join-Path $repo "app/src/main/java/reikai/fill/F$i.kt") "package fill`n" }
    foreach ($path in $files.Keys) { Write-Text (Join-Path $repo $path) $files[$path] }
    Invoke-Git $work init -q (Split-Path $repo -Leaf)
    $mode = @()
    if ($Staged) {
        Invoke-Git $repo add -A -- . ":(exclude)$Staged"
        Invoke-Git $repo commit -q -m before
        $mode = @('-Staged')
    }
    Invoke-Git $repo add -A
    $out = (pwsh -NoProfile -File $commentCap -RepoRoot $repo -MihonClone $clonePath @mode *>&1 | Out-String)
    $got = $LASTEXITCODE
    if ($got -eq $want -and (-not $says -or $out.Contains($says))) {
        Write-Host "  ok    $name"
        $script:pass++
    } else {
        Write-Host "  BROKE $name (wanted exit $want$(if ($says) { " saying '$says'" }), got $got)"
        Write-Host ($out.Trim() -replace '(?m)^', '        ')
        $script:broke++
    }
}

$ownedOutside = 'app/src/main/java/mihon/core/migration/Ours.kt'
try {
    Write-Host 'Reikai-owned files'
    Test-Case 'fails nine lines in a Reikai file outside reikai/' 1 "${ownedOutside}:2: 9 lines" @{ $ownedOutside = "package x`n$nine" }
    Test-Case 'passes eight lines' 0 '' @{ $ownedOutside = "package x`n$eight" }
    Test-Case 'fails nine lines of SQL comments' 1 'data/src/main/sqldelight/ours.sq:1: 9 lines' @{ 'data/src/main/sqldelight/ours.sq' = (Get-Lines '--' 9) + "`nSELECT 1;`n" }
    Test-Case 'counts a KDoc''s prose but not its tag list' 0 '' @{ $ownedOutside = "/**`n" + (Get-Lines ' *' 6) + "`n * @param a one`n * @param b two`n * @return three`n */`nfun f(a: Int, b: Int) = a`n" }
    Test-Case 'fails a KDoc whose prose alone runs past the cap' 1 '9 lines' @{ $ownedOutside = "/**`n" + (Get-Lines ' *' 8) + "`n * @param a one`n */`nfun f(a: Int) = a`n" }

    Write-Host 'Mihon''s files'
    Test-Case 'leaves Mihon''s own comments alone' 0 '' @{ "$m/Shared.kt" = "package m`n$nine" }
    Test-Case 'fails nine lines inside an RK island' 1 "$m/Shared.kt:4: 9 lines" @{ "$m/Shared.kt" = "package m`n`n// RK --> ours`n$nine// RK <--`n" }
    Test-Case 'does not count the island''s own markers' 0 '' @{ "$m/Shared.kt" = "package m`n// RK --> ours`n$eight// RK <--`n" }
    Test-Case 'fails an island in a build script' 1 'app/build.gradle.kts:3: 9 lines' @{ 'app/build.gradle.kts' = "plugins {}`n// RK --> ours`n$nine// RK <--`n" }
    Test-Case 'reads a whole-file rewrite as Reikai''s' 1 "$m/Shared.kt:3: 9 lines" @{ "$m/Shared.kt" = "// RK: whole file, a test`npackage m`n$nine" }

    Write-Host 'modes'
    Test-Case 'fails a wall the commit stages' 1 '9 lines' @{ $ownedOutside = "package x`n$nine" } -Staged $ownedOutside
    Test-Case 'leaves a wall the commit does not stage' 0 '' @{ $ownedOutside = "package x`n$nine"; 'app/src/main/java/reikai/B.kt' = "package b`n" } -Staged 'app/src/main/java/reikai/B.kt'
    Test-Case 'fails without a Mihon clone' 1 'no Mihon clone' @{ $ownedOutside = "package x`n" } (Join-Path $work 'absent')
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "passed=$pass broken=$broke"
if ($broke -ne 0) { exit 1 }
