[CmdletBinding()]
param(
    [ValidateSet("Build", "Test", "Verify", "Package", "Clean", "Ide", "Release", "Diagnose")]
    [string]$Task = "Build",
    [switch]$NoDaemon,
    [switch]$Offline,
    [switch]$Detailed
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($Task -eq "Diagnose") {
    & (Join-Path $Root "scripts/Diagnose-Environment.ps1") -Strict
    if (-not $?) { exit 1 }
    exit 0
}
$DebugScript = Join-Path $Root "scripts/Debug-Build.ps1"
if (-not (Test-Path $DebugScript)) { throw "KSL build diagnostics script is missing: $DebugScript" }
$GradleTasks = switch ($Task) {
    "Build"   { @("clean", "build") }
    "Test"    { @("clean", "test") }
    "Verify"  { @("clean", "check", "verifyShadowJar") }
    "Package" { @("assemble", "verifyShadowJar") }
    "Clean"   { @("clean") }
    "Ide"     { @("-p", "ide", "-PkslJar=../build/libs/KotlinScriptLoader-1.2.0.jar", "verifyKslIdeSetup") }
    "Release" { @("clean", "build", "verifyShadowJar") }
}
Write-Host ""
Write-Host "  K O T L I N S C R I P T L O A D E R" -ForegroundColor Cyan
Write-Host "  Build controller" -ForegroundColor DarkCyan
Write-Host "  Task: $Task" -ForegroundColor White
Write-Host ""
$Arguments = @{ GradleTasks = $GradleTasks; ReportName = if ($Task -eq "Package") { "build" } else { $Task.ToLowerInvariant() } }
if ($NoDaemon) { $Arguments.NoDaemon = $true }
if ($Offline) { $Arguments.Offline = $true }
if ($Detailed) { $Arguments.Detailed = $true }
& $DebugScript @Arguments
exit $LASTEXITCODE
