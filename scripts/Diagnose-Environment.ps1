[CmdletBinding()]
param([switch]$Strict)
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
$Problems = [System.Collections.Generic.List[string]]::new()
$Checks = [System.Collections.Generic.List[object]]::new()
function Add-Check([string]$Name, [string]$State, [string]$Details) {
    $Checks.Add([pscustomobject]@{ name = $Name; status = $State; details = $Details })
    $Color = if ($State -eq "OK") { "Green" } elseif ($State -eq "WARN") { "Yellow" } else { "Red" }
    Write-Host ("[{0,-4}] {1}: {2}" -f $State, $Name, $Details) -ForegroundColor $Color
    if ($State -eq "FAIL") { $Problems.Add("$Name - $Details") }
}
Write-Host ""
Write-Host "KSL ENVIRONMENT DIAGNOSTICS" -ForegroundColor Cyan
Write-Host "Repository: $Root"
Add-Check "PowerShell" "OK" "$($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))"
Add-Check "Operating system" "OK" ([System.Runtime.InteropServices.RuntimeInformation]::OSDescription)
$Java = Get-Command java -ErrorAction SilentlyContinue
if (-not $Java) { Add-Check "Java executable" "FAIL" "java was not found on PATH" }
else {
    $JavaOutput = (& java -version 2>&1 | Out-String)
    if ($JavaOutput -match 'version "21\.') { Add-Check "Java 21" "OK" (($JavaOutput -split '\r?\n')[0].Trim()) }
    else { Add-Check "Java 21" "FAIL" "Expected Java 21; detected: $($JavaOutput.Trim())" }
}
if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) { Add-Check "JAVA_HOME" "OK" $env:JAVA_HOME }
else { Add-Check "JAVA_HOME" "WARN" "Not set or path does not exist; Gradle may use PATH Java" }
$IsWindowsHost = ($env:OS -eq "Windows_NT") -or ($PSVersionTable.PSEdition -eq "Desktop")
$Wrapper = if ($IsWindowsHost) { "gradlew.bat" } else { "gradlew" }
$WrapperPath = Join-Path $Root $Wrapper
if (Test-Path $WrapperPath) { Add-Check "Gradle wrapper" "OK" $Wrapper }
else { Add-Check "Gradle wrapper" "FAIL" "$WrapperPath is missing" }
$Props = Join-Path $Root "gradle/wrapper/gradle-wrapper.properties"
if (Test-Path $Props) {
    $Distribution = (Select-String -Path $Props -Pattern '^distributionUrl=' | Select-Object -First 1).Line
    Add-Check "Gradle distribution" "OK" ($Distribution -replace '^distributionUrl=', '')
} else { Add-Check "Gradle wrapper config" "FAIL" "gradle-wrapper.properties is missing" }
try {
    $DriveRoot = [IO.Path]::GetPathRoot($Root)
    $Drive = [IO.DriveInfo]::new($DriveRoot)
    $Free = [math]::Round($Drive.AvailableFreeSpace / 1GB, 2)
    if ($Free -lt 1) { Add-Check "Disk space" "WARN" "$Free GB free on repository drive" }
    else { Add-Check "Disk space" "OK" "$Free GB free on repository drive" }
} catch { Add-Check "Disk space" "WARN" "Could not read free disk space: $($_.Exception.Message)" }
foreach ($Relative in @("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "src/main/resources/plugin.yml")) {
    if (Test-Path (Join-Path $Root $Relative)) { Add-Check "File $Relative" "OK" "present" }
    else { Add-Check "File $Relative" "FAIL" "missing" }
}
$Git = Get-Command git -ErrorAction SilentlyContinue
if ($Git) {
    $Branch = (& git -C $Root branch --show-current 2>$null)
    $Commit = (& git -C $Root rev-parse --short HEAD 2>$null)
    Add-Check "Git" "OK" "$Branch @ $Commit"
} else { Add-Check "Git" "WARN" "Git executable not found; source-control details unavailable" }
$ReportDir = Join-Path $Root "build-reports"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
[pscustomobject]@{ generatedAt = (Get-Date).ToString("o"); repository = $Root; checks = @($Checks); problems = @($Problems) } |
    ConvertTo-Json -Depth 6 | Set-Content (Join-Path $ReportDir "environment.json") -Encoding UTF8
Write-Host ""
if ($Problems.Count -eq 0) { Write-Host "Environment checks completed." -ForegroundColor Green }
else {
    Write-Host "$($Problems.Count) environment problem(s) found." -ForegroundColor Red
    if ($Strict) { exit 1 }
}
