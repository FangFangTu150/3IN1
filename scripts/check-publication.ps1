$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $files = @(& git -c core.quotepath=false ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Run this check inside the initialized project repository.' }
    $files = @($files | Sort-Object -Unique)
    $blocked = @()
    foreach ($file in $files) {
        if ($file -match '^(DevDoc|dist|\.gradle|\.kotlin|\.idea)/' -or
            $file -match '(^|/)(build|captures)/' -or
            $file -match '(^|/)(local\.properties|\.env(?:\..*)?)$' -or
            $file -match '\.(apk|aab|jks|keystore|p12|pfx|pem|key|log)$' -or
            $file -match '[^\x00-\x7F]') {
            $blocked += "$file : private/generated/unreviewed path"
            continue
        }
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { continue }
        if ((Get-Item -LiteralPath $file).Length -gt 50MB) {
            $blocked += "$file : unexpectedly large repository file"
        }
        if ($file -match '\.(md|kt|kts|xml|properties|yml|yaml|ps1|json|txt)$') {
            $secret = Select-String -LiteralPath $file -Quiet -Pattern @(
                'ghp_[A-Za-z0-9]{30,}',
                'github_pat_[A-Za-z0-9_]{30,}',
                '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
                '[A-Za-z]:[/\\]Users[/\\][^/\\\s]+'
            )
            if ($secret) { $blocked += "$file : possible credential or local user path" }
        }
    }
    if ($blocked.Count -gt 0) {
        $blocked | ForEach-Object { Write-Output $_ }
        throw 'Publication check failed. Review these files; never bypass by force-adding private files.'
    }
    Write-Output "Publication path/pattern check passed for $($files.Count) candidate files."
    Write-Output 'This is a best-effort check, not a substitute for reviewing the complete staged diff.'
} finally {
    Pop-Location
}
