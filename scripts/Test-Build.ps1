[CmdletBinding()]
param(
    [switch]$Detailed,
    [switch]$NoDaemon,
    [switch]$Offline,
    [switch]$OpenReport
)
$Root = Split-Path -Parent $PSScriptRoot
$Arguments = @{ GradleTasks = @("clean", "check", "koverHtmlReport", "koverXmlReport", "componentTestAudit"); ReportName = "verify" }
if ($Detailed) { $Arguments.Detailed = $true }
if ($NoDaemon) { $Arguments.NoDaemon = $true }
if ($Offline) { $Arguments.Offline = $true }
& (Join-Path $PSScriptRoot "Debug-Build.ps1") @Arguments
$Code = $LASTEXITCODE
$Html = Join-Path $Root "build/reports/tests/test/index.html"
$Report = Join-Path $Root "build-reports/index.html"
$Xml = Join-Path $Root "build/test-results/test"
Write-Host ""
Write-Host "TEST OUTPUTS" -ForegroundColor Cyan
if (Test-Path $Html) { Write-Host "Gradle test report: $Html" -ForegroundColor Green }
if (Test-Path $Xml) { Write-Host "JUnit XML results:  $Xml" -ForegroundColor Green }
if (Test-Path $Report) { Write-Host "KSL build report:   $Report" -ForegroundColor Cyan }
if ($OpenReport -and (Test-Path $Report)) { Start-Process $Report }
if ($Code -ne 0) { exit $Code }
