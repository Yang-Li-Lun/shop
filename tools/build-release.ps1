param(
    [Parameter(Mandatory)][string]$SdkRoot,
    [Parameter(Mandatory)][string]$KeystorePath,
    [Parameter(Mandatory)][string]$KeyAlias,
    [string]$StorePasswordEnv = 'CSC_STORE_PASSWORD',
    [string]$KeyPasswordEnv = 'CSC_KEY_PASSWORD'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (!(Test-Path -LiteralPath $KeystorePath)) { throw 'Signing keystore does not exist.' }
if (![Environment]::GetEnvironmentVariable($StorePasswordEnv) -or ![Environment]::GetEnvironmentVariable($KeyPasswordEnv)) {
    throw 'Set the signing password environment variables before building.'
}
$buildTools = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Android build tools not found.' }
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
Push-Location $repoRoot
try {
    $udsPath = Join-Path $repoRoot 'app/build/uds'
    New-Item -ItemType Directory -Force -Path $udsPath | Out-Null
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djdk.net.unixdomain.tmpdir=$udsPath".Trim()
    & .\gradlew.bat --no-daemon testDebugUnitTest lintDebug lintRelease assembleRelease -PtargetAbi=arm64-v8a
    if ($LASTEXITCODE -ne 0) { throw 'Release validation failed.' }
    $unsigned = Join-Path $repoRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'
    $signed = Join-Path $repoRoot 'app/build/outputs/apk/release/CSC-signed-release.apk'
    & (Join-Path $buildTools.FullName 'apksigner.bat') sign --ks $KeystorePath --ks-key-alias $KeyAlias --ks-pass "env:$StorePasswordEnv" --key-pass "env:$KeyPasswordEnv" --out $signed $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $signed
    if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
    Get-FileHash -LiteralPath $signed -Algorithm SHA256
    Write-Output "Validated release: $signed"
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Pop-Location
}