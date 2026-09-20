<#
.SYNOPSIS
    持续监控 Android 模拟器（或真机）上本应用的运行日志。

.DESCRIPTION
    安卓的日志不是宿主机上的一个文件，而是设备端 logcat 的环形缓冲区，要用 adb 拉。
    本脚本把 logcat 持续落到宿主机的日志文件里，同时输出到控制台，便于：
      - 挂在后台跑，随时回看；
      - 应用崩溃 / 模拟器重连后自动续上（外层循环 wait-for-device）。

    采集范围（够定位问题，又不会被系统刷屏）：
      应用自身的 tag（TenderApp / CrawlerHolder / LocalApiServer / CrawlWorker /
      CrawlScheduler / CrawlNotifier / OkHttpTransport / CrawlEngine）
      + AndroidRuntime:E（Java 崩溃堆栈）
      + DEBUG:F / libc:F（native 崩溃）
      + ActivityManager:I（Activity 启动、进程被杀）
    一旦出现 FATAL EXCEPTION，额外把完整 logcat 缓冲区另存一份，方便翻上下文。

    注意：本脚本用 Windows PowerShell 5.1 也能跑，文件必须存成「UTF-8 带 BOM」，
    否则 5.1 会按 GBK 解码中文注释导致语法错误。

.EXAMPLE
    # 后台常驻，默认写到 <仓库>\logs\emu-logcat-<时间>.log
    powershell -ExecutionPolicy Bypass -File .\tools\emulator-log-monitor.ps1

.EXAMPLE
    # 指定模拟器与输出文件
    powershell -ExecutionPolicy Bypass -File .\tools\emulator-log-monitor.ps1 -Serial emulator-5554 -LogFile D:\tmp\emu.log
#>
[CmdletBinding()]
param(
    # adb.exe 路径
    [string]$Adb = 'D:\Android\Sdk\platform-tools\adb.exe',
    # 目标设备序列号；留空表示"只有一台设备时自动选它"
    [string]$Serial = '',
    # 日志文件；留空则写到 <仓库根>\logs\emu-logcat-<时间戳>.log
    [string]$LogFile = '',
    # 只抓这些 tag（应用自己的日志）
    [string[]]$Tags = @('TenderApp', 'CrawlerHolder', 'LocalApiServer', 'CrawlWorker',
                        'CrawlScheduler', 'CrawlNotifier', 'OkHttpTransport', 'CrawlEngine'),
    # 连接后先清空旧缓冲，便于建立干净基线
    [switch]$ClearFirst
)

$ErrorActionPreference = 'Continue'

# 让 PowerShell 按 UTF-8 解码 adb 的输出，否则中文日志会乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-LogLine {
    param([string]$Text)
    [System.IO.File]::AppendAllText($LogFile, $Text + "`r`n", $Utf8NoBom)
    Write-Host $Text
}

if (-not (Test-Path -LiteralPath $Adb)) { throw "找不到 adb：$Adb" }

if ([string]::IsNullOrWhiteSpace($LogFile)) {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $logDir = Join-Path $repoRoot 'logs'
    if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
    $LogFile = Join-Path $logDir ("emu-logcat-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
} else {
    $parent = Split-Path -Parent $LogFile
    if (-not [string]::IsNullOrWhiteSpace($parent) -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
}

# 过滤规则：列出的 tag 放开到 Verbose，其余静音（*:S 必须放最后）
$filter = @()
foreach ($tag in $Tags) { $filter += "${tag}:V" }
$filter += @('AndroidRuntime:E', 'DEBUG:F', 'libc:F', 'ActivityManager:I', '*:S')

$serialArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) { $serialArgs = @('-s', $Serial) }

Write-LogLine "# 模拟器日志监控"
Write-LogLine "# 开始时间 : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-LogLine "# adb      : $Adb"
Write-LogLine "# 设备     : $(if ($Serial) { $Serial } else { '(自动)' })"
Write-LogLine "# 过滤     : $($filter -join ' ')"
Write-LogLine "# ---------------------------------------------------------------"

# 崩溃时把完整缓冲区也存一份，方便事后翻上下文
function Save-FullBufferDump {
    param([string]$Reason)
    $dump = Join-Path (Split-Path -Parent $LogFile) ("emu-logcat-full-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
    Write-LogLine "[monitor] 检测到崩溃，保存完整 logcat 缓冲区：$dump"
    try {
        $all = & $Adb @serialArgs logcat -d -v threadtime 2>&1
        [System.IO.File]::WriteAllText($dump, (($all | Out-String) + "`r`n"), $Utf8NoBom)
    } catch {
        Write-LogLine "[monitor] 保存完整缓冲区失败：$($_.Exception.Message)"
    }
}

$attempt = 0
while ($true) {
    $attempt++
    Write-Host "[monitor] 等待设备…（第 $attempt 次连接）"
    & $Adb @serialArgs wait-for-device 2>&1 | Out-Null

    if ($ClearFirst -and $attempt -eq 1) { & $Adb @serialArgs logcat -c 2>&1 | Out-Null }

    $model = (& $Adb @serialArgs shell getprop ro.product.model 2>&1 | Select-Object -First 1)
    $rel = (& $Adb @serialArgs shell getprop ro.build.version.release 2>&1 | Select-Object -First 1)
    Write-LogLine "[monitor] 已连接：$model (Android $rel)，日志写入 $LogFile"

    # 崩溃关键字：命中就把完整缓冲区另存一份
    $crashPattern = 'FATAL EXCEPTION|beginning of crash|native crash|SIGSEGV'

    & $Adb @serialArgs logcat -v threadtime @filter 2>&1 | ForEach-Object {
        $line = [string]$_
        [System.IO.File]::AppendAllText($LogFile, $line + "`r`n", $Utf8NoBom)
        Write-Host $line
        if ($line -match $crashPattern) {
            Save-FullBufferDump -Reason (($line -replace '\s+', ' ').Trim())
        }
    }

    Write-LogLine "[monitor] logcat 断开（应用或模拟器可能重启了），2 秒后重连…"
    Start-Sleep -Seconds 2
}
