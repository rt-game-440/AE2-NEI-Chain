# 全自动验收：构建 → 部署（autoTest 模式）→ 启动 GTNH 实例 → 等待游戏自测并自动退出 → 输出结果
# 退出码：0 = PASS，1 = FAIL / 超时
param(
    [string]$Instance = 'D:\mc\Prism-GTNH\instances\GTNH-2.9.0-FieldTest',
    [string]$Prism = 'D:\mc\Prism-GTNH\prismlauncher.exe',
    [int]$TimeoutMinutes = 20,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$instanceName = Split-Path -Leaf $Instance
$logDir = Join-Path $Instance '.minecraft/logs/ae2neichain'

if (Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'GT: New Horizons*' }) {
    throw 'GTNH 游戏正在运行，请先关闭再执行自动验收'
}
if (-not $SkipBuild) { & "$here/build.ps1" | Out-Host }
& "$here/deploy.ps1" -Instance $Instance -AutoTest | Out-Host

$startTime = Get-Date
Start-Process -FilePath $Prism -ArgumentList '--launch', $instanceName
"已启动 $instanceName，等待自测结果（最长 $TimeoutMinutes 分钟）..."

$result = $null
$deadline = $startTime.AddMinutes($TimeoutMinutes)
$seenGame = $false
try {
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $log = Get-ChildItem $logDir -Filter '*.log' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $startTime } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($log) {
            $line = Select-String -Path $log.FullName -Pattern 'AUTOTEST RESULT' | Select-Object -Last 1
            if ($line) { $result = $line.Line }
        }
        $game = Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'GT: New Horizons*' }
        if ($game) { $seenGame = $true }
        if ($seenGame -and -not $game -and (Get-Process javaw -ErrorAction SilentlyContinue).Count -eq 0) { break }
    }
} finally {
    # 恢复为普通调试模式，避免下次正常启动时又自动跑测试
    & "$here/deploy.ps1" -Instance $Instance -Debug | Out-Null
}

if ($log) {
    "---- 调试日志: $($log.FullName)"
    Select-String -Path $log.FullName -Pattern 'AUTOTEST|CHAINTEST|REPORT|RECENT|合成请求|包装 overlay|ERROR' | ForEach-Object { $_.Line }
}
if ($result -and $result -match 'PASS') { "==== 结果: PASS"; exit 0 }
"==== 结果: FAIL（$result）"
exit 1
