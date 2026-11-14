[CmdletBinding()]
param()
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
Write-Host "=== KotlinScriptLoader build environment ==="
Write-Host "Repository: $Root"
Write-Host "OS: $([System.Runtime.InteropServices.RuntimeInformation]::OSDescription)"
Write-Host "PowerShell: $($PSVersionTable.PSVersion)"
Write-Host "JAVA_HOME: $env:JAVA_HOME"
if (Get-Command java -ErrorAction SilentlyContinue) {
    Write-Host "--- Java version ---"
    & java -version 2>&1
} else { Write-Warning "java was not found on PATH" }
$Wrapper = if ($IsWindows -or $env:OS -eq "Windows_NT") { Join-Path $Root "gradlew.bat" } else { Join-Path $Root "gradlew" }
Write-Host "Gradle wrapper: $Wrapper"
if (Test-Path $Wrapper) {
    if ($Wrapper.EndsWith(".bat")) { & cmd.exe /c "`"$Wrapper`" --version" }
    else { & bash $Wrapper --version }
} else { Write-Warning "Gradle wrapper is missing" }
Write-Host "=== Environment check complete ==="
