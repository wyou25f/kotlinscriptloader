[CmdletBinding()]
param(
    [ValidateSet("Build", "Test", "Verify", "Clean", "Ide", "Release")]
    [string]$Task = "Build",
    [switch]$NoDaemon,
    [switch]$Offline,
    [switch]$Info
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

function Find-Java21 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(
        "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot",
        "C:\Program Files\Eclipse Adoptium\jdk-21",
        "C:\Program Files\Java\jdk-21"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\java.exe"))) {
            $version = (& (Join-Path $candidate "bin\java.exe") -version 2>&1 | Out-String)
            if ($version -match 'version "21\.') { return $candidate }
        }
    }
    $command = Get-Command java -ErrorAction SilentlyContinue
    if ($command) {
        $version = (& java -version 2>&1 | Out-String)
        if ($version -match 'version "21\.') { return (Split-Path (Split-Path $command.Source -Parent) -Parent) }
    }
    throw "JDK 21 was not found. Set JAVA_HOME to a JDK 21 installation."
}

$env:JAVA_HOME = Find-Java21
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
$gradleArgs = @("--console=plain")
if ($NoDaemon) { $gradleArgs += "--no-daemon" }
if ($Offline) { $gradleArgs += "--offline" }
if ($Info) { $gradleArgs += "--info" }

switch ($Task) {
    "Build"   { $gradleArgs += @("clean", "build") }
    "Test"    { $gradleArgs += @("test") }
    "Verify"  { $gradleArgs += @("clean", "check", "verifyShadowJar") }
    "Clean"   { $gradleArgs += @("clean") }
    "Ide"     { $gradleArgs += @("-p", "ide", "verifyKslIdeSetup") }
    "Release" { $gradleArgs += @("clean", "build", "verifyShadowJar") }
}

Write-Host "KSL task: $Task"
Write-Host "Using JDK: $env:JAVA_HOME"
& (Join-Path $repoRoot "gradlew.bat") @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
