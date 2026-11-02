# Runs git against a test fixture, so the script tests share one identity. Dot-source it.
function Invoke-Git([string]$dir) {
    git -C $dir -c user.name=test -c user.email=test@example.com -c commit.gpgsign=false -c core.autocrlf=false @args | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "git $args failed in $dir" }
}
