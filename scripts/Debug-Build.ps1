[CmdletBinding()]
param(
    [ValidateSet("build", "check", "test", "assemble")]
    [string]$Task = "build",
    [switch]$Detailed
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $Root "build-logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$Log = Join-Path $LogDir "$Task-$Stamp.log"
$Wrapper = if ($IsWindows -or $env:OS -eq "Windows_NT") { Join-Path $Root "gradlew.bat" } else { Join-Path $Root "gradlew" }
if (-not (Test-Path $Wrapper)) { throw "Gradle wrapper not found: $Wrapper" }
$Arguments = @("--no-daemon", "--stacktrace", "--console=plain")
if ($Detailed) { $Arguments += "--info" }
$Arguments += $Task
Push-Location $Root
try {
    Write-Host "Running: $Task"
    Write-Host "Full log: $Log"
    if ($Wrapper.EndsWith(".bat")) { & cmd.exe /c "`"$Wrapper`" $($Arguments -join ' ')" 2>&1 | Tee-Object -FilePath $Log }
    else { & bash $Wrapper @Arguments 2>&1 | Tee-Object -FilePath $Log }
    $ExitCode = $LASTEXITCODE
    Write-Host "Gradle exit code: $ExitCode"
    Write-Host "Log saved to: $Log"
    if ($ExitCode -ne 0) { exit $ExitCode }
} finally { Pop-Location }
