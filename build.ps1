[CmdletBinding()]
param(
    [ValidateSet("Build", "Test", "Verify", "Clean", "Ide", "Release")]
    [string]$Task = "Build",
    [switch]$NoDaemon,
    [switch]$Offline,
    [switch]$Detailed
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$DebugScript = Join-Path $Root "scripts/Debug-Build.ps1"
if (-not (Test-Path $DebugScript)) { throw "KSL build diagnostics script is missing: $DebugScript" }

$GradleTasks = switch ($Task) {
    "Build"   { @("clean", "build") }
    "Test"    { @("clean", "test") }
    "Verify"  { @("clean", "check", "verifyShadowJar") }
    "Clean"   { @("clean") }
    "Ide"     { @("-p", "ide", "-PkslJar=../build/libs/KotlinScriptLoader-1.2.0.jar", "verifyKslIdeSetup") }
    "Release" { @("clean", "build", "-PverifyRelease=true") }
}
Write-Host ""
Write-Host "  K O T L I N S C R I P T L O A D E R" -ForegroundColor Cyan
Write-Host "  Build controller" -ForegroundColor DarkCyan
Write-Host "  Task: $Task" -ForegroundColor White
Write-Host ""
$Arguments = @{ GradleTasks = $GradleTasks; ReportName = $Task.ToLowerInvariant() }
if ($NoDaemon) { $Arguments.NoDaemon = $true }
if ($Offline) { $Arguments.Offline = $true }
if ($Detailed) { $Arguments.Detailed = $true }
& $DebugScript @Arguments
exit $LASTEXITCODE
