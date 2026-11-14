[CmdletBinding()]
param([string]$JarPath = "")
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $Candidate = Get-ChildItem (Join-Path $Root "build/libs") -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "-sources|-javadoc" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $Candidate) { throw "No JAR found in build/libs. Run the build first." }
    $JarPath = $Candidate.FullName
}
$JarPath = (Resolve-Path $JarPath).Path
Add-Type -AssemblyName System.IO.Compression
$Archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
try {
    $Entries = @($Archive.Entries)
    $Descriptor = $Archive.GetEntry("plugin.yml")
    Write-Host "JAR: $JarPath"
    Write-Host ("Size: {0:N2} MB" -f ((Get-Item $JarPath).Length / 1MB))
    Write-Host "Entries: $($Entries.Count)"
    Write-Host "Kotlin runtime entries: $(($Entries | Where-Object { $_.FullName -like 'kotlin/*' -or $_.FullName -like 'kotlinx/*' }).Count)"
    Write-Host "Main class present: $([bool]($Archive.GetEntry('ru/privateserver/ksl/KotlinScriptLoaderPlugin.class')))"
    if ($Descriptor) {
        Write-Host "--- plugin.yml ---"
        $Reader = [System.IO.StreamReader]::new($Descriptor.Open())
        try { Write-Host $Reader.ReadToEnd() } finally { $Reader.Dispose() }
    } else { Write-Warning "plugin.yml is missing" }
    Write-Host "--- Important entries ---"
    $Entries | Where-Object { $_.FullName -match '(^plugin.yml$|KotlinScriptLoaderPlugin.class$|kotlin/jvm/internal/Intrinsics.class$|META-INF/services/)' } |
        ForEach-Object { Write-Host $_.FullName }
} finally { $Archive.Dispose() }
