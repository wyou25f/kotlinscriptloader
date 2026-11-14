[CmdletBinding()]
param([string]$JarPath = "")
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $Candidate = Get-ChildItem (Join-Path $Root "build/libs") -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "-sources|-javadoc" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $Candidate) { throw "No JAR found in build/libs. Run ./build.ps1 first." }
    $JarPath = $Candidate.FullName
}
$JarPath = (Resolve-Path $JarPath).Path
Add-Type -AssemblyName System.IO.Compression
$Archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
$Issues = [System.Collections.Generic.List[string]]::new()
$Details = [System.Collections.Generic.List[object]]::new()
try {
    $Entries = @($Archive.Entries)
    $Descriptor = $Archive.GetEntry("plugin.yml")
    $MainClass = $Archive.GetEntry("ru/privateserver/ksl/KotlinScriptLoaderPlugin.class")
    $KotlinCount = @($Entries | Where-Object { $_.FullName -like "kotlin/*" -or $_.FullName -like "kotlinx/*" }).Count
    $Yaml = ""
    Write-Host ""
    Write-Host "KSL JAR INSPECTOR" -ForegroundColor Cyan
    Write-Host "File: $JarPath"
    Write-Host ("Size: {0:N2} MB | Entries: {1}" -f ((Get-Item $JarPath).Length / 1MB), $Entries.Count)
    Write-Host "Kotlin runtime entries: $KotlinCount"
    if ($MainClass) { Write-Host "[OK] Main class exists" -ForegroundColor Green }
    else { Write-Host "[FAIL] Main class missing" -ForegroundColor Red; $Issues.Add("Main class missing") }
    if ($Descriptor) {
        $Reader = [System.IO.StreamReader]::new($Descriptor.Open())
        try { $Yaml = $Reader.ReadToEnd() } finally { $Reader.Dispose() }
        Write-Host "[OK] plugin.yml exists" -ForegroundColor Green
        if ($Yaml -notmatch '(?m)^main:\s*ru\.privateserver\.ksl\.KotlinScriptLoaderPlugin\s*$') {
            $Issues.Add("plugin.yml main entry is missing or unexpected")
        }
        if ($Yaml -notmatch '(?m)^api-version:\s*[''"]?1\.21') { $Issues.Add("plugin.yml api-version does not appear to target 1.21") }
    } else { $Issues.Add("plugin.yml missing") }

    $Required = @(
        "ru/privateserver/ksl/api/KSLApiProvider.class",
        "ru/privateserver/ksl/api/KSLPublicApiKt.class",
        "ru/privateserver/ksl/rt/KSLCache.class",
        "ru/privateserver/ksl/rt/KSLProfiler.class",
        "ru/privateserver/ksl/rt/KSLRuntimeScope.class"
    )
    foreach ($Entry in $Required) {
        if ($Archive.GetEntry($Entry)) { Write-Host "[OK] $Entry" -ForegroundColor Green }
        else { Write-Host "[FAIL] $Entry" -ForegroundColor Red; $Issues.Add("Required entry missing: $Entry") }
    }
    $DeclaresExternalLibraries = $Yaml -match '(?m)^libraries:\s*\[' -and $Yaml -notmatch '(?m)^libraries:\s*\[\s*\]'
    if ($DeclaresExternalLibraries -and $KotlinCount -gt 0) {
        $Issues.Add("JAR embeds Kotlin runtime but plugin.yml also declares external Kotlin libraries")
    } elseif (-not $DeclaresExternalLibraries -and $KotlinCount -eq 0) {
        $Issues.Add("Neither embedded Kotlin runtime nor external Paper libraries were detected")
    } elseif ($DeclaresExternalLibraries) {
        Write-Host "[OK] Thin-JAR dependency declaration found" -ForegroundColor Green
    } else {
        Write-Host "[OK] Embedded Kotlin runtime found (fat JAR)" -ForegroundColor Green
    }
    $Details.Add([pscustomobject]@{
        file = [IO.Path]::GetFileName($JarPath)
        sizeBytes = (Get-Item $JarPath).Length
        entries = $Entries.Count
        kotlinRuntimeEntries = $KotlinCount
        hasPluginDescriptor = [bool]$Descriptor
        hasMainClass = [bool]$MainClass
    })
} finally { $Archive.Dispose() }

$ReportDir = Join-Path $Root "build-reports"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$Summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    jar = $JarPath
    details = @($Details)
    issueCount = $Issues.Count
    issues = @($Issues)
}
$Summary | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $ReportDir "jar-inspection.json") -Encoding UTF8
if ($Issues.Count -gt 0) {
    Write-Host ""
    Write-Host "$($Issues.Count) issue(s) detected." -ForegroundColor Red
    $Issues | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "JAR inspection passed." -ForegroundColor Green
