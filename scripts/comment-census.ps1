# Comment density per .kt file, for the length cap in .claude/rules/code-quality.md "Comments".
# The hook enforces the per-block cap on commit; this is for looking at a whole tree at once, where
# the useful signal is the ratio (a file above roughly 30% is a smell) rather than any one block.
#
#   pwsh scripts/comment-census.ps1 -Roots app/src/main/java/reikai | Sort-Object Pct -Descending
#
# MaxBlock is the longest run of consecutive comment lines and MaxBlockAt is where it starts. It is
# the RAW run: unlike the hook, it keeps counting through a KDoc tag list, so a heavily-parameterized
# function reads higher here than the hook scores it. Point -Roots at refs/mihon to compare upstream.
param([string[]]$Roots)

function Measure-File($path) {
    $lines = Get-Content -LiteralPath $path
    $inBlock = $false
    $comment = 0; $code = 0; $blank = 0
    $run = 0; $maxRun = 0; $maxRunAt = 0
    $i = 0
    foreach ($raw in $lines) {
        $i++
        $t = $raw.Trim()
        $isComment = $false
        if ($inBlock) {
            $isComment = $true
            if ($t -match '\*/') { $inBlock = $false }
        }
        elseif ($t.StartsWith('/*')) {
            $isComment = $true
            if (-not ($t -match '\*/')) { $inBlock = $true }
        }
        elseif ($t.StartsWith('//')) { $isComment = $true }

        if ($isComment) {
            $comment++; $run++
            if ($run -gt $maxRun) { $maxRun = $run; $maxRunAt = $i - $run + 1 }
        }
        else {
            $run = 0
            if ($t -eq '') { $blank++ } else { $code++ }
        }
    }
    [pscustomobject]@{
        File       = $path
        Total      = $lines.Count
        Comment    = $comment
        Code       = $code
        Blank      = $blank
        Pct        = if ($code -gt 0) { [math]::Round(100 * $comment / ($comment + $code), 1) } else { 0 }
        MaxBlock   = $maxRun
        MaxBlockAt = $maxRunAt
    }
}

$files = foreach ($r in $Roots) { Get-ChildItem -Path $r -Recurse -Filter *.kt -File }
$files | ForEach-Object { Measure-File $_.FullName }
