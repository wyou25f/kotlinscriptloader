[CmdletBinding()]
param()
$Root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "Debug-Build.ps1") -Task test -Detailed
$ExitCode = $LASTEXITCODE
$Reports = Join-Path $Root "build/reports/tests/test/index.html"
$Results = Join-Path $Root "build/test-results/test"
Write-Host "=== Test report locations ==="
if (Test-Path $Reports) { Write-Host "HTML report: $Reports" }
if (Test-Path $Results) { Write-Host "JUnit XML: $Results" }
if (-not (Test-Path $Reports) -and -not (Test-Path $Results)) { Write-Warning "No Gradle test reports found." }
if ($ExitCode -ne 0) { exit $ExitCode }
