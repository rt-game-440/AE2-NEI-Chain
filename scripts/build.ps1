# 编译 mod：Gradle 运行在 JDK 25 上，编译使用 Gradle 自动发现的 Java 8 工具链（~/.gradle/jdks）
param([string[]]$Tasks = @('build'))
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/java.exe")) {
    $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
Push-Location $root
try {
    & ./gradlew.bat @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 失败，退出码 $LASTEXITCODE" }
    Get-ChildItem build/libs/*.jar | Where-Object { $_.Name -notmatch '-(dev|sources)\.jar$' } | ForEach-Object { "产物: $($_.FullName)" }
} finally {
    Pop-Location
}
