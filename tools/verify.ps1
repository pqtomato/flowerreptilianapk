<#
.SYNOPSIS
    跑一遍这个项目全部能自动化的检查：页面静态自检 → 内核单测 → release 打包。

.DESCRIPTION
    本地想知道"我这把改动有没有搞坏东西"时用；接 CI（Gitee Go / GitHub Actions）时
    也是调这一个脚本，避免 CI 里再抄一遍命令、抄漏了步骤。

    三关都过才算成功：
      1) tools/check-mobile-page.js —— 页面 JS 语法、元素 id、接口清单、手机端适配
      2) :core:test              —— 96 个内核用例（1 个联网联调用例默认跳过）
      3) :app:assembleRelease    —— 真的能打出安装包

.PARAMETER SkipBuild
    跳过打包（改的是 core 逻辑、只想快速验证时用）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\verify.ps1
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Continue'

# 本脚本必须存成「UTF-8 带 BOM」：Windows PowerShell 5.1 会把无 BOM 的 .ps1 按 GBK 读，
# 中文注释会直接把语法撑坏（这个坑在 emulator-log-monitor.ps1 上踩过一次）。
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repoRoot = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $repoRoot 'android'
$pageCheck = Join-Path $repoRoot 'tools\check-mobile-page.js'
$gradlew = Join-Path $androidDir 'gradlew.bat'

if (-not (Test-Path -LiteralPath $pageCheck)) { throw "找不到页面自检脚本：$pageCheck" }
if (-not (Test-Path -LiteralPath $gradlew)) { throw "找不到 gradlew：$gradlew" }

$failed = @()

Write-Host "===== 1/3 页面静态自检 =====" -ForegroundColor Cyan
Push-Location $repoRoot
try {
    & node $pageCheck 'android/app/src/main/assets/index.html' 'android/app/src/main/java'
    if ($LASTEXITCODE -ne 0) { $failed += '页面静态自检' }
} finally {
    Pop-Location
}

Write-Host "`n===== 2/3 内核单测 =====" -ForegroundColor Cyan
Push-Location $androidDir
try {
    $tasks = if ($SkipBuild) { ':core:test' } else { ':core:test', ':app:assembleRelease' }
    & $gradlew @tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        $failed += if ($SkipBuild) { '内核单测' } else { '内核单测 / 打包' }
    }
} finally {
    Pop-Location
}

Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host "===== 全部通过 =====" -ForegroundColor Green
    exit 0
}
Write-Host ("===== 失败：" + ($failed -join '、') + " =====") -ForegroundColor Red
exit 1
