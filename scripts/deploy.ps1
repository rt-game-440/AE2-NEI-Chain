# 把构建产物复制到 GTNH 测试实例 mods 目录（替换旧版本），并设置调试/自动验收开关
param(
    [string]$Instance = 'D:\mc\Prism-GTNH\instances\GTNH-2.9.0-FieldTest',
    [switch]$Debug,
    [switch]$AutoTest
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem "$root/build/libs/ae2neichain-*.jar" | Where-Object { $_.Name -notmatch '-(dev|sources)\.jar$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw '找不到构建产物，请先运行 scripts/build.ps1' }
$mods = Join-Path $Instance '.minecraft/mods'
Get-ChildItem $mods -Filter 'ae2neichain-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $jar.FullName $mods
"已部署: $(Join-Path $mods $jar.Name)"

function Set-CfgBool([string]$text, [string]$key, [bool]$value) {
    $v = $value.ToString().ToLower()
    if ($text -match "B:$key=(true|false)") { return $text -replace "B:$key=(true|false)", "B:$key=$v" }
    return $text -replace 'general \{', "general {`n    B:$key=$v"
}
$cfg = Join-Path $Instance '.minecraft/config/ae2neichain.cfg'
$text = if (Test-Path $cfg) { Get-Content $cfg -Raw } else { "general {`n}`n" }
$text = Set-CfgBool $text 'debug' ([bool]($Debug -or $AutoTest))
$text = Set-CfgBool $text 'autoTest' ([bool]$AutoTest)
Set-Content $cfg $text -Encoding UTF8 -NoNewline
"debug=$([bool]($Debug -or $AutoTest)) autoTest=$([bool]$AutoTest)"
