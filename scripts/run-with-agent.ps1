<#
.SYNOPSIS
  一键起 demo-app：挂 opentelemetry-javaagent 与本项目的扩展 jar。

.DESCRIPTION
  做三件事，任一件不满足就直接失败并说清原因 —— 不做"看起来启动了其实没生效"的事：

  1. 构建库 jar 与 demo-app jar（-SkipBuild 可跳过）
  2. 校验 agent jar 与扩展 jar 都真的存在
  3. 校验端口空闲，然后后台起进程，日志落到 demo-app/target/demo.log

  关于第2 条：`-Dotel.javaagent.extensions=` 指向一个**不存在的路径**时，
  agent 会**静默忽略、不报任何错**。所以必须在这里替 agent 把这个坑堵掉 ——
  否则你会以为扩展挂上了，其实没有。

.EXAMPLE
  pwsh -NoProfile -File scripts/run-with-agent.ps1
  pwsh -NoProfile -File scripts/run-with-agent.ps1 -Port 18099
  pwsh -NoProfile -File scripts/run-with-agent.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
  [string] $AgentJar = "D:\apps\opentelemetry-javaagent-2.32.0.jar",
  [int]    $Port     = 18081,
  [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'

$repo    = Split-Path -Parent $PSScriptRoot
$demoDir = Join-Path $repo 'demo-app'
$logFile = Join-Path $demoDir 'target\demo.log'
$errFile = Join-Path $demoDir 'target\demo.err.log'

function Fail($msg) {
    Write-Host ""
    Write-Host "[启动失败] $msg" -ForegroundColor Red
    Write-Host ""
    exit 1
}

# ---- 1. JDK ------------------------------------------------------------------
if (-not $env:JAVA_HOME) {
    Fail "JAVA_HOME 未设置。本项目用 JDK 17 构建、字节码基线 8。`n  例如：`$env:JAVA_HOME='D:\apps\java\jdk-17.0.8'"
}
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { Fail "JAVA_HOME 指向的 java.exe 不存在：$java" }

# ---- 2. 构建 ------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Host "[1/4] 构建扩展 jar ..." -ForegroundColor Cyan
    Push-Location $repo
    try { mvn -B -q -DskipTests package } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { Fail "扩展 jar 构建失败（仓库根目录）" }

    Write-Host "[2/4] 构建 demo-app jar ..." -ForegroundColor Cyan
    Push-Location $demoDir
    try { mvn -B -q -DskipTests package } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { Fail "demo-app 构建失败（demo-app 目录）" }
} else {
    Write-Host "[1/4][2/4] 跳过构建（-SkipBuild）" -ForegroundColor DarkGray
}

# ---- 3. 产物与依赖校验 ---------------------------------------------------------
$libJar = Get-ChildItem (Join-Path $repo 'target\otel-local-telemetry-store-*.jar') -ErrorAction SilentlyContinue |
          Select-Object -First 1
$appJar = Get-ChildItem (Join-Path $demoDir 'target\otel-local-telemetry-store-demo-*.jar') -ErrorAction SilentlyContinue |
          Select-Object -First 1

Write-Host "[3/4] 校验产物 ..." -ForegroundColor Cyan
if (-not $libJar) { Fail "找不到扩展 jar（$repo\target\otel-local-telemetry-store-*.jar）。别加 -SkipBuild，先构建。" }
if (-not $appJar) { Fail "找不到 demo-app jar（$demoDir\target\otel-local-telemetry-store-demo-*.jar）。别加 -SkipBuild，先构建。" }
if (-not (Test-Path $AgentJar)) {
    Fail "找不到 agent jar：$AgentJar`n  下载：https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases`n  或用 -AgentJar 指定路径"
}

# ---- 4. 端口 ------------------------------------------------------------------
function Test-PortFree([int] $p) {
    $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $p)
    try { $l.Start(); $l.Stop(); return $true } catch { return $false } finally { $l.Stop() }
}
if (-not (Test-PortFree $Port)) {
    Fail "端口 $Port 已被占用。Spring 不会自动退让（退让是我们读口的行为，不是演示应用的），`n  换个端口：-Port 18099"
}

# ---- 起 ----------------------------------------------------------------------
Write-Host "[4/4] 启动 ..." -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path (Join-Path $demoDir 'target') | Out-Null
Remove-Item $logFile, $errFile -ErrorAction SilentlyContinue

$args = @(
    "-javaagent:$AgentJar",
    "-Dotel.javaagent.extensions=$($libJar.FullName)",
    # 演示期间不发往任何后端。OTLP 默认打 4318 会一直报错刷屏。
    "-Dotel.traces.exporter=none",
    "-Dotel.metrics.exporter=none",
    "-Dotel.logs.exporter=none",
    "-Dotel.traces.sampler=always_on",
    "-jar", $appJar.FullName,
    "--server.port=$Port"
)
$p = Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $demoDir `
     -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru -WindowStyle Hidden

Write-Host ""
Write-Host "  PID      $($p.Id)" -ForegroundColor Green
Write-Host "  页面     http://localhost:$Port/" -ForegroundColor Green
Write-Host "  扩展 jar $($libJar.Name)" -ForegroundColor DarkGray
Write-Host "  日志     $logFile" -ForegroundColor DarkGray
Write-Host ""
Write-Host "造点数据看效果：" -ForegroundColor Cyan
Write-Host "  pwsh -NoProfile -Command Invoke-RestMethod -Method Post 'http://localhost:$Port/demo/spans?count=3&childPerSpan=2'"
Write-Host "  pwsh -NoProfile -Command Invoke-RestMethod 'http://localhost:$Port/demo/stats'"
Write-Host ""
Write-Host "注意：PowerShell 7 的 Invoke-RestMethod 不认 NO_PROXY，本机设了代理的话" -ForegroundColor Yellow
Write-Host "      访问 localhost 会超时。用 curl.exe --noproxy \"*\"，或加 -NoProxy 参数。" -ForegroundColor Yellow
Write-Host ""
Write-Host "停掉：Stop-Process -Id $($p.Id)" -ForegroundColor DarkGray
Write-Host "看日志：Get-Content '$logFile' -Encoding UTF8 -Wait" -ForegroundColor DarkGray