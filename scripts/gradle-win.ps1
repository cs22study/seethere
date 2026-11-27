param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs = @(":core-stream:test", ":app:testDebugUnitTest", ":app:assembleDebug")
)

$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "C:\Program Files\Microsoft" -Filter "jdk-17*" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $sdk) { $env:ANDROID_HOME = $sdk }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$usedLetters = (Get-PSDrive -PSProvider FileSystem).Name
$freeLetter = @("P","Q","R","S","T","U","V","W","X","Y","Z") | Where-Object { $_ -notin $usedLetters } | Select-Object -First 1
if (-not $freeLetter) {
    throw "No free drive letter available for subst ASCII mapping."
}

$drive = "${freeLetter}:"
cmd /c "subst $drive `"$repoRoot`"" | Out-Null
try {
    Push-Location "$drive\"
    & .\gradlew.bat @GradleArgs --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
    cmd /c "subst $drive /D" | Out-Null
}
