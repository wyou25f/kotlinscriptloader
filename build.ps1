[CmdletBinding()]
param(
    [ValidateSet("Build", "Test", "Verify", "Package", "Clean", "Ide", "Release", "Diagnose")]
    [string]$Task = "Build",
    [switch]$NoDaemon,
    [switch]$Offline,
    [switch]$Detailed,
    [switch]$SkipPreflight
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$IsWindowsHost = ($env:OS -eq "Windows_NT") -or ($PSVersionTable.PSEdition -eq "Desktop")

function Stop-Build([string]$Message) {
    Write-Host "PREFLIGHT FAILED: $Message" -ForegroundColor Red
    exit 1
}

if ($Task -eq "Diagnose") {
    & (Join-Path $Root "scripts/Diagnose-Environment.ps1") -Strict
    exit $LASTEXITCODE
}

$RequiredFiles = @(
    "settings.gradle.kts", "build.gradle.kts",
    "gradle/wrapper/gradle-wrapper.properties",
    "src/main/resources/plugin.yml",
    "scripts/Debug-Build.ps1", "scripts/Diagnose-Environment.ps1"
)
foreach ($Relative in $RequiredFiles) {
    if (-not (Test-Path (Join-Path $Root $Relative) -PathType Leaf)) {
        Stop-Build "Required project file is missing: $Relative"
    }
}

$Wrapper = if ($IsWindowsHost) { Join-Path $Root "gradlew.bat" } else { Join-Path $Root "gradlew" }
if (-not (Test-Path $Wrapper -PathType Leaf)) { Stop-Build "Gradle wrapper not found: $Wrapper" }

if (-not $SkipPreflight) {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Stop-Build "Java 21 is required, but java was not found on PATH."
    }
    $JavaOutput = (& java -version 2>&1 | Out-String)
    if ($JavaOutput -notmatch 'version "21\.') {
        Stop-Build "KSL requires Java 21. Detected: $($JavaOutput.Trim())"
    }
    if ($env:JAVA_HOME -and -not (Test-Path $env:JAVA_HOME -PathType Container)) {
        Stop-Build "JAVA_HOME points to a missing directory: $env:JAVA_HOME"
    }
}

$GradleBuild = Get-Content -Raw (Join-Path $Root "build.gradle.kts")
if ($GradleBuild -notmatch 'version\s*=\s*"([^"]+)"') {
    Stop-Build "Could not determine project version from build.gradle.kts."
}
$ProjectVersion = $Matches[1]
if ($ProjectVersion -ne "1.2.0") {
    Stop-Build "This release line must remain 1.2.0; found $ProjectVersion in build.gradle.kts."
}

$GradleTasks = switch ($Task) {
    "Build"   { @("clean", "build") }
    "Test"    { @("clean", "test") }
    "Verify"  { @("clean", "check", "verifyShadowJar") }
    "Package" { @("assemble", "verifyShadowJar") }
    "Clean"   { @("clean") }
    "Ide"     {
        $Jar = Join-Path $Root "build/libs/KotlinScriptLoader-$ProjectVersion.jar"
        if (-not (Test-Path $Jar -PathType Leaf)) {
            Stop-Build "IDE verification requires the built JAR: $Jar. Run .\build.ps1 Build first."
        }
        @("-p", "ide", "-PkslJar=../build/libs/KotlinScriptLoader-$ProjectVersion.jar", "verifyKslIdeSetup")
    }
    "Release" { @("clean", "build", "verifyShadowJar") }
}

Write-Host ""
Write-Host "  K O T L I N S C R I P T L O A D E R" -ForegroundColor Cyan
Write-Host "  Strict build controller" -ForegroundColor DarkCyan
Write-Host "  Version: $ProjectVersion" -ForegroundColor Gray
Write-Host "  Task: $Task" -ForegroundColor White
Write-Host ""

$DebugScript = Join-Path $Root "scripts/Debug-Build.ps1"
$Arguments = @{
    GradleTasks = $GradleTasks
    ReportName = if ($Task -eq "Package") { "build" } else { $Task.ToLowerInvariant() }
}
if ($NoDaemon) { $Arguments.NoDaemon = $true }
if ($Offline) { $Arguments.Offline = $true }
if ($Detailed) { $Arguments.Detailed = $true }

& $DebugScript @Arguments
$BuildExitCode = $LASTEXITCODE
if ($BuildExitCode -ne 0) {
    Write-Host "KSL task '$Task' failed with exit code $BuildExitCode." -ForegroundColor Red
    exit $BuildExitCode
}

if ($Task -in @("Build", "Verify", "Package", "Release")) {
    $ExpectedJar = Join-Path $Root "build/libs/KotlinScriptLoader-$ProjectVersion.jar"
    if (-not (Test-Path $ExpectedJar -PathType Leaf)) {
        Stop-Build "Gradle succeeded but the expected plugin JAR is missing: $ExpectedJar"
    }
    $JarInfo = Get-Item $ExpectedJar
    if ($JarInfo.Length -lt 1MB) {
        Stop-Build "Generated plugin JAR is suspiciously small ($($JarInfo.Length) bytes): $ExpectedJar"
    }
    Write-Host "Strict artifact check passed: $($JarInfo.Name), $($JarInfo.Length) bytes." -ForegroundColor Green
}

exit 0
