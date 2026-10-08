<#
.SYNOPSIS
Print a debug build's SharedPreferences with every secret masked.

.DESCRIPTION
Reads a preferences file from the device over `run-as` and prints one line per entry: the key, the
type and the value. Keys holding credentials (the `__PRIVATE_` prefix PreferenceStore gives tracker,
proxy and source secrets, everything LN plugins and IReader extensions store, and every value in a
source's own `source_<id>.xml`) print only their value's length, so a
dump never puts a token or cookie on screen or in a transcript. Use this instead of `cat`-ing the
file and filtering it, which prints a secret whenever the filter is broader than intended.

.EXAMPLE
pwsh scripts/dump-prefs.ps1 -Match 'hidden_chapters'
.EXAMPLE
pwsh scripts/dump-prefs.ps1 -File active_novel_downloads.xml
#>
param(
    # Regex matched against the key only, never against the value.
    [string]$Match = '.',
    [string]$File = '',
    [string]$Package = 'app.reikai.dev',
    [string]$Serial = $env:ANDROID_SERIAL
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
if (-not $File) { $File = "${Package}_preferences.xml" }
$deviceArgs = if ($Serial) { @('-s', $Serial) } else { @() }

$raw = & $adb @deviceArgs exec-out run-as $Package cat "shared_prefs/$File"
if ($LASTEXITCODE -ne 0 -or -not $raw -or ($raw -join '') -like 'run-as:*') {
    throw "Could not read shared_prefs/$File from $Package (run-as works only on a debuggable build)."
}
[xml]$xml = ($raw -join "`n")

# A source's own prefs file (source_<id>.xml) and plugin storage keys are named by third-party code,
# so nothing says which of their values is a credential: all of them are masked.
$maskAll = $File -like 'source_*'
function Test-Secret([string]$key) {
    $maskAll -or $key.StartsWith('__PRIVATE_') -or $key -match '^(ln|ireader)_storage::'
}

foreach ($node in $xml.map.ChildNodes) {
    if ($node.NodeType -ne 'Element') { continue }
    $key = $node.GetAttribute('name')
    if ($key -notmatch $Match) { continue }
    $type = $node.LocalName
    $value = switch ($type) {
        'set' { '{' + (($node.ChildNodes | ForEach-Object { $_.InnerText }) -join ', ') + '}' }
        'string' { $node.InnerText }
        default { $node.GetAttribute('value') }
    }
    if (Test-Secret $key) { $value = "<masked, $($value.Length) chars>" }
    "{0} ({1}) = {2}" -f $key, $type, $value
}
