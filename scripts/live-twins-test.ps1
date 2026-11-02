<#
.SYNOPSIS
    Checks that scripts/live-twins.ps1 reports a changed Mihon file and nothing else.

.DESCRIPTION
    Builds a two-commit Mihon fixture where Changed.kt moves and Quiet.kt does not, and a table with a
    row for each. A rule that stops matching looks exactly like a quiet sync, so each rule has a case
    that must report as well as one that must not.

    Run it after touching live-twins.ps1: pwsh scripts/live-twins-test.ps1
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'live-twins.ps1')
. (Join-Path $PSScriptRoot 'test-git.ps1')
$work = Join-Path ([IO.Path]::GetTempPath()) ("live-twins-test-" + [guid]::NewGuid().ToString('N'))
$broke = 0

function Assert-That([string]$name, [bool]$held) {
    if ($held) { Write-Host "ok   $name" } else { Write-Host "FAIL $name"; $script:broke++ }
}

try {
    New-Item -ItemType Directory -Path $work | Out-Null
    $mihon = Join-Path $work 'mihon'
    Invoke-Git $work init -q mihon
    New-Item -ItemType Directory -Path (Join-Path $mihon 'app') | Out-Null
    'a' | Set-Content (Join-Path $mihon 'app/Changed.kt')
    'a' | Set-Content (Join-Path $mihon 'app/Quiet.kt')
    Invoke-Git $mihon add -A
    Invoke-Git $mihon commit -q -m base
    $base = (git -C $mihon rev-parse HEAD).Trim()
    'b' | Set-Content (Join-Path $mihon 'app/Changed.kt')
    Invoke-Git $mihon add -A
    Invoke-Git $mihon commit -q -m head

    $table = Join-Path $work 'twins.md'
    @(
        '# Live twins',
        '',
        '| Mihon file | Reikai twin | Pin |',
        '|---|---|---|',
        '| app/Changed.kt | app/NovelChanged.kt | `kernel` |',
        '| app/Quiet.kt | app/NovelQuiet.kt | none |'
    ) | Set-Content $table
    $twins = @(Get-LiveTwins $table)
    Assert-That 'the header and rule rows are not read as twins' ($twins.Count -eq 2)

    $empty = Join-Path $work 'empty.md'
    @('# Live twins', '', '| Mihon file | Reikai twin | Pin |', '|---|---|---|', '| ./app/X.kt | app/Y.kt | none |') | Set-Content $empty
    $threw = $false
    try { Get-LiveTwins $empty | Out-Null } catch { $threw = $true }
    Assert-That 'a table with no readable rows throws' $threw

    $changes = @(Get-LiveTwinChanges $mihon $base 'HEAD' $twins)
    Assert-That 'a changed Mihon file is reported' ($changes.Count -eq 1 -and $changes[0] -match 'Changed\.kt')
    Assert-That 'the report names the twin and its pin' ($changes[0] -match 'NovelChanged\.kt' -and $changes[0] -match 'kernel')
    Assert-That 'a range past the change reports nothing' (@(Get-LiveTwinChanges $mihon 'HEAD' 'HEAD' $twins).Count -eq 0)

    $repo = Join-Path $work 'repo'
    New-Item -ItemType Directory -Path (Join-Path $repo 'app') | Out-Null
    foreach ($name in 'Changed', 'NovelChanged', 'Quiet') { 'x' | Set-Content (Join-Path $repo "app/$name.kt") }
    $stale = @(Get-StaleLiveTwins $repo $twins)
    Assert-That 'a row naming a missing file is stale' ($stale.Count -eq 1 -and $stale[0] -eq 'app/NovelQuiet.kt')
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

if ($broke -gt 0) { Write-Host "$broke case(s) failed"; exit 1 }
Write-Host 'live-twins: all cases pass'
