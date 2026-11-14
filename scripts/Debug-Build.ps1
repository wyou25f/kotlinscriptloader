[CmdletBinding()]
param(
    [string[]]$GradleTasks = @("build"),
    [ValidateSet("build", "test", "verify", "quality", "audit", "ide", "custom", "clean", "release")]
    [string]$ReportName = "build",
    [switch]$Detailed,
    [switch]$Offline,
    [switch]$NoDaemon
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$ReportDir = Join-Path $Root "build-reports"
$LogDir = Join-Path $ReportDir "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$LogPath = Join-Path $LogDir "$ReportName-$Stamp.log"
$JsonPath = Join-Path $ReportDir "latest.json"
$HtmlPath = Join-Path $ReportDir "index.html"
$IsWindowsHost = ($env:OS -eq "Windows_NT") -or ($PSVersionTable.PSEdition -eq "Desktop")
$Wrapper = if ($IsWindowsHost) { Join-Path $Root "gradlew.bat" } else { Join-Path $Root "gradlew" }
if (-not (Test-Path $Wrapper)) { throw "Gradle wrapper not found: $Wrapper" }

$Arguments = @("--console=plain", "--stacktrace")
if ($NoDaemon) { $Arguments += "--no-daemon" }
if ($Offline) { $Arguments += "--offline" }
if ($Detailed) { $Arguments += "--info" }
$Arguments += $GradleTasks

$Started = Get-Date
$ExitCode = 0
$OutputLines = [System.Collections.Generic.List[string]]::new()
Write-Host "KSL build diagnostics" -ForegroundColor Cyan
Write-Host "Command: $Wrapper $($Arguments -join ' ')" -ForegroundColor Gray
Write-Host "Log: $LogPath" -ForegroundColor Gray

Push-Location $Root
try {
    if ($IsWindowsHost) {
        & $Wrapper @Arguments 2>&1 |
            ForEach-Object {
                $Line = "$_"
                $OutputLines.Add($Line)
                Write-Output $Line
            } | Tee-Object -FilePath $LogPath
    } else {
        & bash $Wrapper @Arguments 2>&1 |
            ForEach-Object {
                $Line = "$_"
                $OutputLines.Add($Line)
                Write-Output $Line
            } | Tee-Object -FilePath $LogPath
    }
    $ExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

$Finished = Get-Date
$Duration = [math]::Round(($Finished - $Started).TotalSeconds, 2)
$TaskLines = @($OutputLines | Where-Object { $_ -match '^> Task ' })
$FailureLines = @($OutputLines | Where-Object {
    $_ -match '(?i)(^e: file:|\.kt:\d+:\d+: error:|what went wrong|caused by:|execution failed|could not resolve|could not find|unresolved reference| FAILED$|exception:|assertionerror|tests completed, \d+ failed)'
} | Select-Object -Last 80)
$Report = [ordered]@{
    project = "KotlinScriptLoader"
    task = $ReportName
    status = if ($ExitCode -eq 0) { "success" } else { "failed" }
    exitCode = $ExitCode
    startedAt = $Started.ToString("yyyy-MM-dd HH:mm:ss zzz")
    finishedAt = $Finished.ToString("yyyy-MM-dd HH:mm:ss zzz")
    durationSeconds = $Duration
    machine = $env:COMPUTERNAME
    operatingSystem = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    powershell = $PSVersionTable.PSVersion.ToString()
    javaHome = $env:JAVA_HOME
    gradleTasks = $GradleTasks
    logFile = [IO.Path]::GetRelativePath($Root, $LogPath).Replace("\", "/")
    taskOutput = $TaskLines
    failures = $FailureLines
    testSummary = @($OutputLines | Where-Object { $_ -match '(?i)tests completed|tests failed|TOTAL:' } | Select-Object -Last 10)
}
$Json = $Report | ConvertTo-Json -Depth 8
$Json | Set-Content -Path $JsonPath -Encoding UTF8

$TemplatePath = Join-Path $PSScriptRoot "Build-Report.html"
$CssPath = Join-Path $PSScriptRoot "Build-Report.css"
if ((Test-Path $TemplatePath) -and (Test-Path $CssPath)) {
    $Html = Get-Content -Raw -Path $TemplatePath
    $Html = $Html.Replace("__KSL_REPORT_JSON__", $Json.Replace("</", "<\/"))
    $Html | Set-Content -Path $HtmlPath -Encoding UTF8
    Copy-Item $CssPath (Join-Path $ReportDir "Build-Report.css") -Force
} else {
    Write-Warning "HTML report template or stylesheet is missing; JSON report was still written."
}

Write-Host ""
if ($ExitCode -eq 0) {
    Write-Host "BUILD SUCCEEDED in $Duration sec" -ForegroundColor Green
} else {
    Write-Host "BUILD FAILED (exit $ExitCode) after $Duration sec" -ForegroundColor Red
    if ($FailureLines.Count -gt 0) {
        Write-Host "Detected failure details:" -ForegroundColor Yellow
        $FailureLines | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
    }
}
Write-Host "Report: $HtmlPath" -ForegroundColor Cyan
Write-Host "JSON:   $JsonPath" -ForegroundColor Gray
Write-Host "Log:    $LogPath" -ForegroundColor Gray
$global:LASTEXITCODE = $ExitCode
