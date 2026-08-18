<#
.SYNOPSIS
    Fails when a Metro-owned type is mis-owned: registered with Injekt as well, unscoped, or never
    annotated for the graph at all.

.DESCRIPTION
    During the Injekt-to-Metro port (docs/dev/plans/metro-di-migration.md) a type that moves into
    the graph has its Injekt registration deleted and is handed back through MetroInteropModule.
    Three ways that goes wrong, none of which surfaces at build or run time:

      1. Registered in both places, so the app runs with two instances and loses state silently.
      2. Handed back without @SingleIn(AppScope::class). Injekt's addSingletonFactory caches its
         result forever, but an unscoped Metro binding builds a new instance per injection, so
         Injekt callers and graph callers end up on different objects.
      3. Still Injekt-registered and never annotated, so the port walked past it.

    Injekt module files are discovered, not listed: a hard-coded list goes stale as the port deletes
    files, and a check that silently scans nothing reports success.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

$srcRoots = @(
    'app/src/main/java'
    'domain/src/main/java'
    'data/src/main/java'
    'core/common/src/main/kotlin'
    'source-api/src/main/kotlin'
    'source-local/src/main/kotlin'
    'presentation-widget/src/main/java'
) | ForEach-Object { Join-Path $RepoRoot $_ } | Where-Object { Test-Path -LiteralPath $_ }

if ($srcRoots.Count -eq 0) {
    Write-Error "di-interop-check: no source roots found under '$RepoRoot'. The path list is stale."
    exit 1
}

$sources = @(Get-ChildItem -LiteralPath $srcRoots -Recurse -Filter *.kt -File)
if ($sources.Count -lt 100) {
    Write-Error "di-interop-check: only $($sources.Count) Kotlin files found. The path list is stale."
    exit 1
}

$interopFile = Join-Path $RepoRoot 'app/src/main/java/mihon/app/di/injekt/MetroInteropModule.kt'
if (-not (Test-Path -LiteralPath $interopFile)) {
    Write-Host "di-interop-check: no interop module, nothing to check."
    exit 0
}

# Types the graph hands back: the constructor parameter types of MetroInteropModule.
$interopTypes = [System.Collections.Generic.List[string]]::new()
foreach ($line in Get-Content -LiteralPath $interopFile) {
    if ($line -match '^\s*private val \w+:\s*Provider<([A-Za-z0-9_.]+)>') {
        $interopTypes.Add(($Matches[1] -split '\.')[-1])
    } elseif ($line -match '^\s*private val \w+:\s*([A-Za-z0-9_.]+),\s*$') {
        $interopTypes.Add(($Matches[1] -split '\.')[-1])
    }
}

if ($interopTypes.Count -eq 0) {
    Write-Error "di-interop-check: parsed no types out of MetroInteropModule. The parser is stale."
    exit 1
}

# Every Injekt module in the tree, found by what it is rather than by where it used to live.
$moduleFiles = @($sources | Where-Object {
        [System.IO.File]::ReadAllText($_.FullName) -match ':\s*InjektModule\b'
    } | Where-Object { $_.FullName -ne $interopFile } | ForEach-Object { $_.FullName })

# Index every declaration with its annotation block and its supertype list, so an interface handed
# back by the interop module can be resolved to the implementation that carries the scope.
$declPattern = '(?m)^((?:@[\w\.]+(?:\([^\r\n]*\))?[ \t]*\r?\n)*)' +
'(?:public |internal |private |open |abstract |sealed |data |value |inner )*(?:class|object|interface) ' +
'([A-Za-z0-9_]+)[^\r\n{]*'
$decls = @{}
foreach ($file in $sources) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    foreach ($m in [regex]::Matches($text, $declPattern)) {
        $name = $m.Groups[2].Value
        if ($decls.ContainsKey($name)) { continue }
        $decls[$name] = [pscustomobject]@{
            Path        = $file.FullName
            Annotations = $m.Groups[1].Value
            Header      = $m.Value
            HasMetro    = $text.Contains('dev.zacsweers.metro')
        }
    }
}

# Signatures are matched against whitespace-collapsed text, because a constructor or parameter list
# split across lines is exactly what a line-oriented pattern misses.
$flatScoped = @($sources | ForEach-Object {
        $text = [System.IO.File]::ReadAllText($_.FullName)
        if ($text.Contains('@SingleIn(AppScope::class)')) { ($text -replace '\s+', ' ') }
    })

function Test-Scoped([string]$typeName) {
    $decl = $decls[$typeName]
    if ($decl -and $decl.Annotations -match '@SingleIn\(AppScope::class\)') { return $true }

    # An implementation bound to this interface, or a @Provides function returning it, may carry the
    # scope instead. The supertype match starts after the constructor's closing paren so a parameter
    # of this type cannot be mistaken for a supertype.
    $asSupertype = "@SingleIn\(AppScope::class\)[^{]{0,300}?\b(?:class|object) \w+ ?(?:\([^)]*\) ?)?: [^{]*?\b$typeName\b"
    $asReturnType = "@SingleIn\(AppScope::class\)[^{]{0,200}?\bfun \w+ ?\([^)]*\) ?: $typeName\b"
    foreach ($flat in $flatScoped) {
        if (-not $flat.Contains($typeName)) { continue }
        if ($flat -match $asSupertype -or $flat -match $asReturnType) { return $true }
    }
    return $false
}

$unscoped = @()
foreach ($type in $interopTypes) {
    if (-not (Test-Scoped $type)) {
        $unscoped += "  $type is handed back to Injekt without @SingleIn(AppScope::class)"
    }
}

$violations = @()
$unannotated = @()
foreach ($file in $moduleFiles) {
    $rel = $file.Substring($RepoRoot.Length).TrimStart('\', '/')
    $lines = Get-Content -LiteralPath $file
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -notmatch 'add(Singleton|Factory|SingletonFactory|LazySingleton)') { continue }

        # The constructor can sit on the trigger line or on the next one, which is how a multi-line
        # registration used to slip past both checks.
        $probe = if ($i + 1 -lt $lines.Count) { "$line`n$($lines[$i + 1])" } else { $line }

        $registered = $null
        $ctor = $null
        if ($line -match 'add\w+<\s*([A-Za-z0-9_.]+)\s*[>,]') {
            $registered = ($Matches[1] -split '\.')[-1]
        }
        if ($probe -match 'add\w+(?:<[^>]*>)?\s*\{\s*(?:\r?\n\s*)?([A-Z][A-Za-z0-9_]*)\s*\(') {
            $ctor = $Matches[1]
            if (-not $registered) { $registered = $ctor }
        }

        if ($registered -and $interopTypes -contains $registered) {
            $violations += "  $rel`:$($i + 1) registers $registered, which MetroInteropModule already hands back"
        }
        if ($ctor) {
            $decl = $decls[$ctor]
            if ($decl -and -not $decl.HasMetro) {
                $unannotated += "  $rel`:$($i + 1) registers $ctor, whose class carries no Metro annotation"
            }
        }
    }
}

$failed = $false
foreach ($group in @(
        @{ Message = 'a registered class was never annotated for the graph.'; Items = $unannotated
            Hint = 'Annotate it, or drop the registration if the type is genuinely retired.'
        },
        @{ Message = 'a type is registered with Injekt and handed back by Metro.'; Items = $violations
            Hint = 'Delete the Injekt registration, or drop the type from MetroInteropModule. Never both.'
        },
        @{ Message = 'a type handed back to Injekt is not an application singleton.'; Items = $unscoped
            Hint = 'Injekt caches its instance forever while an unscoped graph binding builds a new one per injection, so the two halves of the app drift apart. Add @SingleIn(AppScope::class).'
        }
    )) {
    if ($group.Items.Count -gt 0) {
        $failed = $true
        Write-Host "di-interop-check FAILED: $($group.Message)" -ForegroundColor Red
        $group.Items | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        Write-Host ""
        Write-Host $group.Hint
        Write-Host ""
    }
}
if ($failed) { exit 1 }

$moduleLabel = if ($moduleFiles.Count -eq 0) { 'no Injekt modules left' } else { "$($moduleFiles.Count) Injekt module(s)" }
Write-Host "di-interop-check: $($interopTypes.Count) graph-owned types, all scoped, $moduleLabel, no duplicates."
exit 0
