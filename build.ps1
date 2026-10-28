# Build on JDK 21: the Kotlin 2.0.21 compiler cannot parse Java 25 version strings (IllegalArgumentException: 25.0.x).
$jdk = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"

if (-not (Test-Path $jdk)) {
    Write-Error "JDK not found: $jdk (update the path in this script)"
    exit 1
}

$env:JAVA_HOME = $jdk
.\gradlew.bat clean build -x test
