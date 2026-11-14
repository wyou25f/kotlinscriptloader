[CmdletBinding()]
param(
    [switch]$IncludeBuildOutput,
    [switch]$RunEnvironmentCheck
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$ReportDir = Join-Path $Root "build-reports"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null

if ($RunEnvironmentCheck) {
    & (Join-Path $PSScriptRoot "Diagnose-Environment.ps1")
}
if ($IncludeBuildOutput -and (Test-Path (Join-Path $Root "build/libs"))) {
    & (Join-Path $PSScriptRoot "Inspect-Jar.ps1")
}

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$Archive = Join-Path $ReportDir "KSL-diagnostics-$Stamp.zip"
$Stage = Join-Path ([IO.Path]::GetTempPath()) "KSL-diagnostics-$Stamp"
New-Item -ItemType Directory -Force -Path $Stage | Out-Null
try {
    $Candidates = @(
        (Join-Path $Root "build-reports"),
        (Join-Path $Root "build/reports/tests/test"),
        (Join-Path $Root "build/test-results/test")
    )
    foreach ($Source in $Candidates) {
        if (-not (Test-Path $Source)) { continue }
        $Name = if ($Source.StartsWith((Join-Path $Root "build-reports"))) { "build-reports" }
            elseif ($Source -eq (Join-Path $Root "build/reports/tests/test")) { "gradle-test-html" }
            else { "junit-xml" }
        $Destination = Join-Path $Stage $Name
        Copy-Item $Source $Destination -Recurse -Force
    }
    if (-not (Get-ChildItem $Stage -Force -ErrorAction SilentlyContinue)) {
        throw "No diagnostics found. Run ./build.ps1 -Task Test or ./build.ps1 -Task Build first."
    }
    Compress-Archive -Path (Join-Path $Stage "*") -DestinationPath $Archive -CompressionLevel Optimal -Force
} finally {
    Remove-Item $Stage -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host ""
Write-Host "Diagnostics archive created:" -ForegroundColor Green
Write-Host $Archive -ForegroundColor Cyan
Write-Warning "Review the archive before sharing it; logs can contain local paths, usernames, and environment details."
