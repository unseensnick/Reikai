<#
.SYNOPSIS
    Fails on a new cross-file token clone in Reikai's own Kotlin (every main-source file Mihon does not
    have, plus the RK islands in the files it does) that scripts/dup-baseline.txt does not already list.

.DESCRIPTION
    The DRY rule in .claude/rules/code-quality.md asks for a reuse search before a helper is written.
    This is the mechanical half: a copy-paste detector in the style of PMD's CPD. Identifiers, string
    and number literals are each normalised to one token, so a copy with renamed variables still
    matches, and a clone is a run of at least -MinTokens identical tokens in two different files.
    Comments, imports and the package line are ignored. A window shared by more than six places is
    boilerplate (a constructor shape, a when ladder) and is skipped.

    The baseline keys each clone by its file pair plus a hash of its normalised tokens, never by line
    numbers, so an unrelated edit above a baselined clone does not re-fire it. Editing the cloned code
    itself changes the hash, which is the point: touching a known duplicate re-asks the question.

    Ownership is read from Mihon's tree at the synced base (the top row of the ledger in
    docs/dev/upstream-sync.md) and at the clone's HEAD: the base still has a file upstream renamed
    after it, and HEAD has one Reikai ported ahead of the base. A path in neither is Reikai's and is
    read whole; a Mihon file is read only inside its RK islands, or whole under a whole-file header.
    The clone defaults to refs/mihon beside the main worktree, and a missing one fails the check. A
    blobless clone (--filter=blob:none) is enough, which is what CI takes.

    Modes: the whole tree (CI), -Staged (pre-commit: only clones touching a staged file), and
    -UpdateBaseline (rewrite the baseline from the current tree, for a deliberate, explained clone).
    Reads the working tree, as di-interop-check.ps1 does.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$MihonClone,
    [switch]$Staged,
    [switch]$UpdateBaseline,
    [int]$MinTokens = 100
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mihon-ownership.ps1')
$baselinePath = Join-Path $RepoRoot 'scripts/dup-baseline.txt'

if (-not $MihonClone) { $MihonClone = Join-Path (Get-DefaultRefsRoot $RepoRoot) 'mihon' }
if (-not (Test-Path -LiteralPath $MihonClone)) {
    Write-Host "dup-check: no Mihon clone at $MihonClone, which says which files are Reikai's. Clone mihonapp/mihon there (--filter=blob:none is enough) or pass -MihonClone."
    exit 1
}
$base = Get-SyncedMihonBase $RepoRoot
$mihonPaths = [System.Collections.Generic.HashSet[string]]::new()
# A hook exports the commit's GIT_DIR and index, which would point these calls back at Reikai.
$hookGitVars = 'GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE'
$savedGitVars = @{}
foreach ($v in $hookGitVars) {
    $savedGitVars[$v] = [Environment]::GetEnvironmentVariable($v)
    Remove-Item "Env:$v" -ErrorAction SilentlyContinue
}
try {
    foreach ($rev in $base, 'HEAD') {
        $paths = @(git -C $MihonClone ls-tree -r --name-only "$rev^{commit}" 2>$null)
        if ($LASTEXITCODE -ne 0) {
            Write-Host "dup-check: the Mihon clone at $MihonClone has no commit $rev. Pull it."
            exit 1
        }
        foreach ($p in $paths) { [void]$mihonPaths.Add($p) }
    }
} finally {
    foreach ($v in $hookGitVars) { if ($null -ne $savedGitVars[$v]) { Set-Item "Env:$v" $savedGitVars[$v] } }
}

# The scope, as git sees it: walking folders instead swept worktree copies under .claude/ in the spike.
# ReikaiIcons.kt is generated vector paths, and exh/metadata/ is Komikku's metadata model ported
# verbatim, whose per-source classes repeat one shape by design.
$scope = '(^|/)src/main/.*\.kt$'
$excluded = '(/ReikaiIcons\.kt$|/exh/metadata/)'
$tracked = @(git -C $RepoRoot ls-files -- '*.kt' | Where-Object { $_ -match $scope -and $_ -notmatch $excluded } | Sort-Object)
if ($LASTEXITCODE -ne 0) {
    Write-Error 'dup-check: git ls-files failed.'
    exit 1
}

$files = [System.Collections.Generic.List[string]]::new()
$texts = [System.Collections.Generic.List[string]]::new()
$islandErrors = [System.Collections.Generic.List[string]]::new()
$owned = 0
foreach ($f in $tracked) {
    $text = [System.IO.File]::ReadAllText((Join-Path $RepoRoot $f))
    if (-not $mihonPaths.Contains($f)) {
        $owned++
    } elseif ($text.Contains('RK')) {
        $lines = $text -split "`r?`n"
        if (-not (Test-RkWholeFile $lines)) {
            $islands = Get-RkIslands $lines
            if ($islands.Error) { $islandErrors.Add("${f}: $($islands.Error)") }
            # Lines outside the islands are blanked rather than dropped, so reported line numbers hold.
            $kept = for ($n = 1; $n -le $lines.Count; $n++) { if ($islands.Mask[$n]) { $lines[$n - 1] } else { '' } }
            $text = $kept -join "`n"
            if ($text.Trim().Length -eq 0) { continue }
        }
    } else {
        continue
    }
    $files.Add($f)
    $texts.Add($text)
}
# A scope regex that silently stops matching, or an ownership read that claims everything for Mihon,
# would report a clean tree.
if ($owned -lt 300) {
    Write-Error "dup-check: only $owned Reikai-owned Kotlin files in scope. The scope pattern or the Mihon clone is stale."
    exit 1
}

$touched = $null
if ($Staged -and -not $UpdateBaseline) {
    $inScope = [System.Collections.Generic.HashSet[string]]::new([string[]]$tracked)
    $touched = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($f in @(git -C $RepoRoot diff --cached --name-only --diff-filter=ACMR -- '*.kt')) {
        if ($inScope.Contains($f)) { [void]$touched.Add($f) }
    }
    if ($touched.Count -eq 0) { exit 0 }
}

# An unbalanced island hides Reikai's lines from this check, or hands it Mihon's.
$badIslands = @($islandErrors | Where-Object { $null -eq $touched -or $touched.Contains(($_ -split ': ', 2)[0]) })
if ($badIslands.Count -gt 0) {
    Write-Host 'dup-check: unbalanced RK islands (see .claude/rules/architecture.md):'
    foreach ($e in $badIslands) { Write-Host "  $e" }
    exit 1
}

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

public sealed class DupClone {
    public int Tokens; public string Hash;
    public string FileA; public int LineA1; public int LineA2;
    public string FileB; public int LineB1; public int LineB2;
    public string Key { get { return FileA + "\t" + FileB + "\t" + Hash; } }
}

public static class DupDetector {
    // Strings are matched first so a "//" inside a URL literal is not taken for a comment.
    static readonly Regex Comments = new Regex(
        @"""""""[\s\S]*?""""""|""(?:\\.|[^""\\\n])*""|'(?:\\.|[^'\\\n])*'|/\*[\s\S]*?\*/|//[^\n]*");
    static readonly Regex Tokens = new Regex(
        @"""""""[\s\S]*?""""""|""(?:\\.|[^""\\])*""|'(?:\\.|[^'\\])*'|[A-Za-z_][A-Za-z0-9_]*|\d[\d_.xXa-fA-FLlFf]*|==|!=|<=|>=|&&|\|\||->|::|\?\.|\?:|\S");
    static readonly Regex ImportLine = new Regex(@"^[ \t]*(import|package)\b[^\n]*", RegexOptions.Multiline);
    static readonly HashSet<string> Keywords = new HashSet<string>(
        ("package import class interface object fun val var if else when for while do return break continue " +
         "try catch finally throw is in as null true false this super override private internal public protected data sealed " +
         "enum companion suspend inline operator infix lateinit const abstract open annotation typealias init get set by where " +
         "out reified crossinline noinline value").Split(' '));

    static string Normalise(string t) {
        char c = t[0];
        if (c == '"' || c == '\'') return "S";
        if (char.IsDigit(c)) return "N";
        if ((char.IsLetter(c) || c == '_') && !Keywords.Contains(t)) return "I";
        return t;
    }

    public static List<DupClone> Find(string[] names, string[] texts, int w) {
        int n = names.Length;
        var norms = new string[n][]; var lines = new int[n][]; var ids = new int[n][];
        var vocab = new Dictionary<string, int>(StringComparer.Ordinal);
        for (int f = 0; f < n; f++) {
            string src = Comments.Replace(texts[f].Replace("\r\n", "\n"), m =>
                m.Value.StartsWith("/") ? new string('\n', m.Value.Count(ch => ch == '\n')) : m.Value);
            src = ImportLine.Replace(src, "");
            var lineStarts = new List<int> { 0 };
            for (int i = 0; i < src.Length; i++) if (src[i] == '\n') lineStarts.Add(i + 1);
            var nt = new List<string>(); var nl = new List<int>(); var ni = new List<int>();
            foreach (Match m in Tokens.Matches(src)) {
                string t = Normalise(m.Value);
                int line = lineStarts.BinarySearch(m.Index);
                if (line < 0) line = ~line - 1;
                int id;
                if (!vocab.TryGetValue(t, out id)) { id = vocab.Count + 1; vocab[t] = id; }
                nt.Add(t); nl.Add(line + 1); ni.Add(id);
            }
            norms[f] = nt.ToArray(); lines[f] = nl.ToArray(); ids[f] = ni.ToArray();
        }

        // Rolling hash of every w-token window.
        const ulong B = 1000003UL;
        ulong pow = 1; for (int i = 0; i < w - 1; i++) pow = unchecked(pow * B);
        var index = new Dictionary<ulong, List<long>>();
        for (int f = 0; f < n; f++) {
            int[] s = ids[f];
            if (s.Length < w) continue;
            ulong h = 0;
            for (int i = 0; i < w; i++) h = unchecked(h * B + (ulong)s[i]);
            for (int i = 0; ; i++) {
                List<long> l;
                if (!index.TryGetValue(h, out l)) { l = new List<long>(2); index[h] = l; }
                l.Add(((long)f << 32) | (uint)i);
                if (i + w >= s.Length) break;
                h = unchecked((h - (ulong)s[i] * pow) * B + (ulong)s[i + w]);
            }
        }

        // Matching windows at a fixed offset between two files form one clone region.
        var pairs = new Dictionary<Tuple<int, int, int>, List<int>>();
        foreach (var locs in index.Values) {
            if (locs.Count < 2 || locs.Count > 6) continue;
            for (int a = 0; a < locs.Count; a++) for (int b = a + 1; b < locs.Count; b++) {
                int fa = (int)(locs[a] >> 32), ia = (int)(locs[a] & 0xffffffff);
                int fb = (int)(locs[b] >> 32), ib = (int)(locs[b] & 0xffffffff);
                if (fa == fb) continue;
                // Ordinal, so the key reads the same on every machine whatever its culture.
                if (string.CompareOrdinal(names[fa], names[fb]) > 0) { int t = fa; fa = fb; fb = t; t = ia; ia = ib; ib = t; }
                var key = Tuple.Create(fa, fb, ia - ib);
                List<int> starts;
                if (!pairs.TryGetValue(key, out starts)) { starts = new List<int>(); pairs[key] = starts; }
                starts.Add(ia);
            }
        }

        var result = new List<DupClone>();
        using (var sha = SHA256.Create()) {
            foreach (var kv in pairs) {
                int fa = kv.Key.Item1, fb = kv.Key.Item2, off = kv.Key.Item3;
                var starts = kv.Value; starts.Sort();
                int runStart = starts[0], prev = starts[0];
                for (int k = 1; k <= starts.Count; k++) {
                    if (k < starts.Count && starts[k] == prev + 1) { prev = starts[k]; continue; }
                    int endA = prev + w - 1;
                    string body = string.Join(" ", norms[fa], runStart, endA - runStart + 1);
                    string hash = BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(body)), 0, 8).Replace("-", "").ToLowerInvariant();
                    result.Add(new DupClone {
                        Tokens = endA - runStart + 1, Hash = hash,
                        FileA = names[fa], LineA1 = lines[fa][runStart], LineA2 = lines[fa][endA],
                        FileB = names[fb], LineB1 = lines[fb][runStart - off], LineB2 = lines[fb][endA - off]
                    });
                    if (k < starts.Count) { runStart = prev = starts[k]; }
                }
            }
        }
        return result.OrderByDescending(c => c.Tokens).ThenBy(c => c.Key, StringComparer.Ordinal).ToList();
    }
}
'@

$clones = [DupDetector]::Find($files.ToArray(), $texts.ToArray(), $MinTokens)

if ($UpdateBaseline) {
    $header = @(
        '# Cross-file token clones in Reikai-owned Kotlin and RK islands that predate their check or were'
        '# kept on purpose. One per line: file A, file B, hash of the normalised tokens (tab-separated).'
        '# Regenerate with: pwsh scripts/dup-check.ps1 -UpdateBaseline, and say in the commit why a new one'
        '# is kept.'
    )
    $keys = @($clones | ForEach-Object Key | Sort-Object -Unique)
    # LF on every platform, so a baseline regenerated on Windows diffs cleanly against one from CI.
    [System.IO.File]::WriteAllText($baselinePath, ((@($header) + $keys) -join "`n") + "`n")
    Write-Host "dup-check: baseline rewritten with $($keys.Count) clone(s)."
    exit 0
}

if (-not (Test-Path -LiteralPath $baselinePath)) {
    Write-Error "dup-check: $baselinePath is missing. Generate it with -UpdateBaseline."
    exit 1
}
$baseline = [System.Collections.Generic.HashSet[string]]::new()
foreach ($line in [System.IO.File]::ReadAllLines($baselinePath)) {
    if ($line -and -not $line.StartsWith('#')) { [void]$baseline.Add($line) }
}

$new = @($clones | Where-Object {
        -not $baseline.Contains($_.Key) -and ($null -eq $touched -or $touched.Contains($_.FileA) -or $touched.Contains($_.FileB))
    })

if ($null -eq $touched) {
    $live = [System.Collections.Generic.HashSet[string]]::new([string[]]@($clones | ForEach-Object Key))
    $gone = @($baseline | Where-Object { -not $live.Contains($_) }).Count
    if ($gone -gt 0) {
        Write-Host "dup-check: $gone baselined clone(s) no longer found; prune with -UpdateBaseline."
    }
}

if ($new.Count -eq 0) { exit 0 }

Write-Host "dup-check: $($new.Count) new cross-file clone(s) of $MinTokens+ tokens (identifiers renamed):"
foreach ($c in $new) {
    Write-Host ("  {0,5} tokens  {1}:{2}-{3}  ~  {4}:{5}-{6}" -f $c.Tokens, $c.FileA, $c.LineA1, $c.LineA2, $c.FileB, $c.LineB1, $c.LineB2)
}
Write-Host 'Share the code (a kernel both call, an extracted helper), or if the copy is deliberate, run'
Write-Host 'pwsh scripts/dup-check.ps1 -UpdateBaseline and say why in the commit. See code-quality.md (DRY).'
exit 1
