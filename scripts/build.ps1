param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # Keep Windows JDK local sockets in a short, ASCII path.
    $previous = $env:JAVA_TOOL_OPTIONS
    $env:JAVA_TOOL_OPTIONS = "$previous -Djava.net.preferIPv4Stack=true -Djdk.net.unixdomain.tmpdir=$root"
    $tasks = @(':app:assembleCompat', ':app:lintCompat')
    if (-not $SkipTests) { $tasks += ':app:testCompatUnitTest' }
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    New-Item -ItemType Directory -Path (Join-Path $root 'dist') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'app\build\outputs\apk\compat\app-compat.apk') `
        -Destination (Join-Path $root 'dist\3IN1-0.1.8-compat.apk')
    $hash = Get-FileHash -LiteralPath (Join-Path $root 'dist\3IN1-0.1.8-compat.apk') -Algorithm SHA256
    "$($hash.Hash.ToLowerInvariant())  3IN1-0.1.8-compat.apk" |
        Set-Content -LiteralPath (Join-Path $root 'dist\3IN1-0.1.8-compat.apk.sha256') -Encoding ascii
    $hash
} finally {
    $env:JAVA_TOOL_OPTIONS = $previous
    Pop-Location
}
