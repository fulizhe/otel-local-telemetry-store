#Requires -Version 7.0
<#
.SYNOPSIS
  占住一个 TCP 端口，用来验读口的「端口退让」分支。

.DESCRIPTION
  读口默认绑 17890；绑定失败时退到随机端口，并把实际端口写进 <dataDir>/otelstore.port。
  要验这条分支，必须先让 17890 真的被占住 —— 只配一个不存在的端口不算，那是换端口，不是退让。

  绑 0.0.0.0 而不是 127.0.0.1：读口默认也绑 0.0.0.0，通配地址必须先被占住，
  它才会真的拿到 BindException。

  两个窗口：这个窗口占着端口，另一个窗口起 scripts/run-with-agent.ps1。
  验完在这个窗口按 Enter（或 Ctrl-C）释放。

.EXAMPLE
  pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/hold-port.ps1
  pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/hold-port.ps1 -Port 17890
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [int]$Port = 17890
)

$ErrorActionPreference = 'Stop'

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
try {
    $listener.Start()
} catch {
    Write-Host "端口 $Port 占不住：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "可能已经被别的进程占着 —— 那样读口一样会退让，但你会分不清是谁占的。" -ForegroundColor Yellow
    exit 1
}

try {
    Write-Host ""
    Write-Host "已占用 0.0.0.0:$Port（本进程 PID $PID）" -ForegroundColor Yellow
    Write-Host "现在到另一个窗口起读口：" -ForegroundColor Cyan
    Write-Host "  pwsh -NoProfile -File scripts/run-with-agent.ps1 -SkipBuild" -ForegroundColor Gray
    Write-Host "启动横幅会显示「读口 http://localhost:<别的端口>/」并提示退让。" -ForegroundColor Gray
    Write-Host ""
    Write-Host "验完在这里按 Enter 释放端口。" -ForegroundColor Yellow
    [void](Read-Host)
} finally {
    $listener.Stop()
    Write-Host "已释放 $Port。" -ForegroundColor Green
}
