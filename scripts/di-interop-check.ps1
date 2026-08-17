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

# Second check: a class the Injekt modules still register must carry a Metro annotation, or it is a
# type the port walked past. This is invisible at build and run time, because the Injekt registration
# keeps it working, so nothing else surfaces it. It is how six classes were missed once already.
$srcRoots = @(
    'app/src/main/java'
    'domain/src/main/java'
    'data/src/main/java'
    'core/common/src/main/kotlin'
    'source-local/src/main/kotlin'
) | ForEach-Object { Join-Path $RepoRoot $_ } | Where-Object { Test-Path -LiteralPath $_ }

$declIndex = @{}
foreach ($root in $srcRoots) {
    Get-ChildItem -LiteralPath $root -Recurse -Filter *.kt -File | ForEach-Object {
        $text = [System.IO.File]::ReadAllText($_.FullName)
        $hasMetro = $text.Contains('dev.zacsweers.metro')
        foreach ($m in [regex]::Matches($text, '(?m)^(?:internal )?class ([A-Za-z0-9_]+)')) {
            $name = $m.Groups[1].Value
            if (-not $declIndex.ContainsKey($name)) {
                $declIndex[$name] = [pscustomobject]@{ Path = $_.FullName; HasMetro = $hasMetro }
            }
        }
    }
}

$unannotated = @()
foreach ($file in $moduleFiles) {
    $rel = $file.Substring($RepoRoot.Length).TrimStart('\', '/')
    $lineNo = 0
    foreach ($line in Get-Content -LiteralPath $file) {
        $lineNo++
        if ($line -notmatch 'add(Singleton|Factory|SingletonFactory|LazySingleton)') { continue }
        if ($line -notmatch 'add\w+(?:<[^>]+>)?\s*\{\s*([A-Z][A-Za-z0-9_]*)\s*\(') { continue }
        $ctor = $Matches[1]
        $decl = $declIndex[$ctor]
        if ($decl -and -not $decl.HasMetro) {
            $unannotated += "  $rel`:$lineNo registers $ctor, whose class carries no Metro annotation"
        }
    }
}

if ($unannotated.Count -gt 0) {
    Write-Host "di-interop-check FAILED: a registered class was never annotated for the graph." -ForegroundColor Red
    $unannotated | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    Write-Host ""
    Write-Host "Annotate it, or drop the registration if the type is genuinely retired."
    exit 1
}

if ($violations.Count -gt 0) {
    Write-Host "di-interop-check FAILED: a type is registered with Injekt and handed back by Metro." -ForegroundColor Red
    $violations | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    Write-Host ""
    Write-Host "Delete the Injekt registration, or drop the type from MetroInteropModule. Never both."
    exit 1
}

Write-Host "di-interop-check: $($interopTypes.Count) graph-owned types, no Injekt duplicates, every registered class annotated."
exit 0
