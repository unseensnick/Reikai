<#
.SYNOPSIS
    The deep-audit ledger: findings a run refuted, kept only while the code they describe is unchanged.
.DESCRIPTION
    ledger.jsonl holds one row per line: file, symbol, line, claim, reason, kind, doc, hash, range.
    hash is the file's blob at HEAD when the row was written, so a row's status is decided by the code:
    "holds" while the file's blob at HEAD still matches, "recheck" once the file has changed.

      load    Rows for the workflow's ground.ledger, one "| status | location | claim | kind: reason |"
              line each. Rows whose file no longer exists at HEAD are skipped.
      add     Append the rows in -Path (a JSON array of {file, symbol, line, claim, reason, kind, doc,
              range}), hashing each file at HEAD. kind is false-premise, harmless or upstream; a design
              reason is recorded in the area's subsystem doc instead, and kind design is accepted only
              with doc naming where. A finding a test or shared rule already pins needs no row.
      prune   Rewrite the ledger without the rows whose file no longer exists at HEAD.
      render  The ledger as a Markdown table, with each row's status.
.EXAMPLE
    pwsh .claude/skills/deep-audit/ledger.ps1 load
#>
param(
    [Parameter(Mandatory, Position = 0)][ValidateSet('load', 'add', 'prune', 'render')][string]$Command,
    [string]$Path
)
$ErrorActionPreference = 'Stop'
$ledger = Join-Path $PSScriptRoot 'ledger.jsonl'
$kinds = 'false-premise', 'harmless', 'upstream', 'design'

function Read-Rows {
    if (-not (Test-Path $ledger)) { return @() }
    Get-Content $ledger -Encoding utf8 | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json }
}

# The file's blob at HEAD, or $null when HEAD has no such file.
function Get-Blob([string]$file) {
    $blob = git rev-parse --verify --quiet "HEAD:$file" 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $blob) { return $null }
    $blob.Trim()
}

function Get-Status($row) {
    $blob = Get-Blob $row.file
    if (-not $blob) { return 'gone' }
    if ($blob -eq $row.hash) { 'holds' } else { 'recheck' }
}

function Format-Location($row) {
    $loc = $row.file + $(if ($row.line) { ":$($row.line)" } else { '' })
    if ($row.symbol) { "$loc ($($row.symbol))" } else { $loc }
}

function Clean([string]$s) { ($s -replace '\|', '/' -replace '\r?\n', ' ').Trim() }

switch ($Command) {
    'load' {
        foreach ($row in Read-Rows) {
            $status = Get-Status $row
            if ($status -eq 'gone') { continue }
            "| $status | $(Format-Location $row) | $(Clean $row.claim) | $($row.kind): $(Clean $row.reason) |"
        }
    }
    'add' {
        if (-not $Path) { throw 'add needs -Path, a JSON array of rows' }
        $new = @(Get-Content $Path -Raw -Encoding utf8 | ConvertFrom-Json)
        $lines = foreach ($r in $new) {
            if ($r.kind -notin $kinds) { throw "kind '$($r.kind)' on $($r.file): use $($kinds -join ', ')" }
            if ($r.kind -eq 'design' -and -not $r.doc) { throw "design row on $($r.file) needs doc, the file that records the decision" }
            if (-not $r.reason) { throw "row on $($r.file) has no reason" }
            $blob = Get-Blob $r.file
            if (-not $blob) { throw "$($r.file) does not exist at HEAD" }
            [ordered]@{
                file = $r.file; symbol = $r.symbol; line = $r.line; claim = $r.claim; reason = $r.reason
                kind = $r.kind; doc = $r.doc; hash = $blob; range = $r.range
            } | ConvertTo-Json -Compress
        }
        Add-Content -Path $ledger -Value $lines -Encoding utf8
        "added $(@($lines).Count) row(s)"
    }
    'prune' {
        $rows = @(Read-Rows)
        $kept = @($rows | Where-Object { (Get-Status $_) -ne 'gone' })
        Set-Content -Path $ledger -Value ($kept | ForEach-Object { $_ | ConvertTo-Json -Compress }) -Encoding utf8
        "kept $($kept.Count) of $($rows.Count)"
    }
    'render' {
        '| Status | Location | Claim | Kind | Refuted because |'
        '|---|---|---|---|---|'
        foreach ($row in Read-Rows) {
            "| $(Get-Status $row) | ``$(Format-Location $row)`` | $(Clean $row.claim) | $($row.kind) | $(Clean $row.reason) |"
        }
    }
}
