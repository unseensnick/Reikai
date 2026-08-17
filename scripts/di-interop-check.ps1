<#
.SYNOPSIS
    Fails when a type is owned by the Metro graph and still registered with Injekt.

.DESCRIPTION
    During the Injekt-to-Metro port (docs/dev/plans/metro-di-migration.md) a type that moves into
    the graph has its Injekt registration deleted and is handed back through MetroInteropModule.
    Registered in both places the app runs with two instances of it and loses state silently
    instead of crashing, so nothing surfaces the mistake at build or run time.

    This compares the types MetroInteropModule hands back against the types the Injekt module
    files still register, and fails on any overlap.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

$interopFile = Join-Path $RepoRoot 'app/src/main/java/mihon/app/di/injekt/MetroInteropModule.kt'
$moduleFiles = @(
    'app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt'
    'app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt'
    'app/src/main/java/eu/kanade/domain/DomainModule.kt'
) | ForEach-Object { Join-Path $RepoRoot $_ } | Where-Object { Test-Path -LiteralPath $_ }

if (-not (Test-Path -LiteralPath $interopFile)) {
    Write-Host "di-interop-check: no interop module, nothing to check."
    exit 0
}

# Types the graph hands back: the constructor parameter types of MetroInteropModule.
$interopTypes = [System.Collections.Generic.HashSet[string]]::new()
foreach ($line in Get-Content -LiteralPath $interopFile) {
    if ($line -match '^\s*private val \w+:\s*Provider<([A-Za-z0-9_.]+)>') {
        [void]$interopTypes.Add(($Matches[1] -split '\.')[-1])
    } elseif ($line -match '^\s*private val \w+:\s*([A-Za-z0-9_.]+),\s*$') {
        [void]$interopTypes.Add(($Matches[1] -split '\.')[-1])
    }
}

if ($interopTypes.Count -eq 0) {
    Write-Error "di-interop-check: parsed no types out of MetroInteropModule. The parser is stale."
    exit 1
}

# Types the Injekt modules still register, by explicit type argument or by constructor call.
$violations = @()
foreach ($file in $moduleFiles) {
    $rel = $file.Substring($RepoRoot.Length).TrimStart('\', '/')
    $lineNo = 0
    foreach ($line in Get-Content -LiteralPath $file) {
        $lineNo++
        if ($line -notmatch 'add(Singleton|Factory|SingletonFactory|LazySingleton)') { continue }

        $registered = $null
        if ($line -match 'add\w+<\s*([A-Za-z0-9_.]+)\s*>') {
            $registered = ($Matches[1] -split '\.')[-1]
        } elseif ($line -match 'add\w+\s*\{\s*([A-Za-z0-9_]+)\s*\(') {
            $registered = $Matches[1]
        } elseif ($line -match 'addSingleton\s*\(\s*(\w+)\s*\)') {
            continue  # a captured instance (the Application), never graph-owned
        }

        if ($registered -and $interopTypes.Contains($registered)) {
            $violations += "  $rel`:$lineNo registers $registered, which MetroInteropModule already hands back"
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "di-interop-check FAILED: a type is registered with Injekt and handed back by Metro." -ForegroundColor Red
    $violations | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    Write-Host ""
    Write-Host "Delete the Injekt registration, or drop the type from MetroInteropModule. Never both."
    exit 1
}

Write-Host "di-interop-check: $($interopTypes.Count) graph-owned types, no Injekt duplicates."
exit 0
