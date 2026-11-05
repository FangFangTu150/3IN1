param([switch]$SkipTests, [switch]$Offline, [string]$InitScript)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # Keep Windows JDK local sockets in a short, ASCII path.
    $previous = $env:JAVA_TOOL_OPTIONS
    $env:JAVA_TOOL_OPTIONS = "$previous -Djava.net.preferIPv4Stack=true -Djdk.net.unixdomain.tmpdir=$root"
    $tasks = @(':app:assembleCompat', ':app:lintCompat')
    if (-not $SkipTests) { $tasks += ':app:testCompatUnitTest' }
    $options = @('--console=plain')
    if ($Offline) { $options += '--offline' }
    if ($InitScript) { $options += @('--init-script', $InitScript) }
    & .\gradlew.bat @tasks @options
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    New-Item -ItemType Directory -Path (Join-Path $root 'dist') -Force | Out-Null
    $metadata = Get-Content -Raw -LiteralPath (Join-Path $root 'app\build\outputs\apk\compat\output-metadata.json') |
        ConvertFrom-Json
    if ($metadata.applicationId -ne 'io.github.threeinone' -or $metadata.elements.Count -ne 1) {
        throw 'Unexpected compat artifact metadata.'
    }
    $artifact = $metadata.elements[0]
    if ($artifact.versionName -notmatch '^\d+\.\d+\.\d+$' -or
        $artifact.outputFile -ne [IO.Path]::GetFileName($artifact.outputFile)) {
        throw 'Invalid version or APK filename in artifact metadata.'
    }
    $name = "3IN1-$($artifact.versionName)-compat.apk"
    Copy-Item -LiteralPath (Join-Path $root "app\build\outputs\apk\compat\$($artifact.outputFile)") `
        -Destination (Join-Path $root "dist\$name")
    $hash = Get-FileHash -LiteralPath (Join-Path $root "dist\$name") -Algorithm SHA256
    "$($hash.Hash.ToLowerInvariant())  $name" |
        Set-Content -LiteralPath (Join-Path $root "dist\$name.sha256") -Encoding ascii
    $hash
} finally {
    $env:JAVA_TOOL_OPTIONS = $previous
    Pop-Location
}
