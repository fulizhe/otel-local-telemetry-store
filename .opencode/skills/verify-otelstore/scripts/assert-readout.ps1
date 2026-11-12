#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('summary')]
    [string]$Command = 'summary',

    [string]$BaseUrl,

    [string]$DataDir,

    [string]$Token,

    [switch]$Json
)

$ErrorActionPreference = 'Continue'

function Stop-WithUsageError {
    param([string]$Message)
    [Console]::Error.WriteLine("assert-readout: $Message")
    exit 2
}

if (-not $BaseUrl -and -not $DataDir) {
    $candidates = @('demo-app\otel-local-telemetry-store', 'otel-local-telemetry-store')
    $match = $candidates | Where-Object { Test-Path -LiteralPath (Join-Path (Get-Location) (Join-Path $_ 'otelstore.port')) } | Select-Object -First 1
    if (-not $match) {
        Stop-WithUsageError "没找到 otelstore.port。试过 demo-app\otel-local-telemetry-store 与 .\otel-local-telemetry-store；用 -DataDir 或 -BaseUrl 指定。"
    }
    $DataDir = $match
}

if (-not $DataDir) {
    $DataDir = 'otel-local-telemetry-store'
}

$resolvedDataDir = [IO.Path]::GetFullPath($DataDir)

function Invoke-Readout {
    param([string]$Url, [string]$Token)

    $bodyFile = [IO.Path]::GetTempFileName()
    $errorFile = [IO.Path]::GetTempFileName()
    $curlArgs = @('--noproxy', '*', '-sS', '-m', '10', '-o', $bodyFile, '-w', '%{http_code}')
    if ($Token) {
        $curlArgs += @('-H', "X-Otel-Store-Token: $Token")
    }
    $curlArgs += $Url

    $statusText = & curl.exe @curlArgs 2>$errorFile
    $exitCode = $LASTEXITCODE

    $body = if (Test-Path -LiteralPath $bodyFile) { [IO.File]::ReadAllText($bodyFile) } else { '' }
    $curlError = if (Test-Path -LiteralPath $errorFile) { [IO.File]::ReadAllText($errorFile) } else { '' }
    Remove-Item -LiteralPath $bodyFile, $errorFile -Force -ErrorAction SilentlyContinue

    $status = 0
    foreach ($line in @($statusText)) {
        if ("$line" -match '^\d{3}$') { $status = [int]$line }
    }

    [pscustomobject]@{
        Status   = $status
        ExitCode = $exitCode
        Body     = $body
        Error    = $curlError.Trim()
    }
}

if (-not $BaseUrl) {
    $portFile = Join-Path $resolvedDataDir 'otelstore.port'
    if (-not (Test-Path -LiteralPath $portFile)) {
        Stop-WithUsageError "找不到端口文件 $portFile。先跑 scripts/run-with-agent.ps1，或用 -BaseUrl 指定读口地址。"
    }
    $portText = (Get-Content -LiteralPath $portFile -Raw).Trim()
    if ($portText -notmatch '^\d+$') {
        Stop-WithUsageError "端口文件内容不是数字：$portFile"
    }
    $BaseUrl = "http://127.0.0.1:$portText"
}

$BaseUrl = $BaseUrl.TrimEnd('/')

if (-not $Token) {
    $tokenFile = Join-Path $resolvedDataDir 'otelstore.token'
    if (Test-Path -LiteralPath $tokenFile) {
        $Token = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
    }
}

$url = "$BaseUrl/api/summary"
$response = Invoke-Readout -Url $url -Token $Token

if ($response.ExitCode -ne 0) {
    Stop-WithUsageError "连不上 $url（curl 退出码 $($response.ExitCode)）：$($response.Error)"
}

$checks = [System.Collections.Generic.List[object]]::new()

function Add-Check {
    param([string]$Id, [string]$Title, [bool]$Pass, [string]$Detail)
    $checks.Add([pscustomobject]@{ id = $Id; title = $Title; pass = $Pass; detail = $Detail })
}

$httpDetail = "status=$($response.Status)"
if ($response.Status -eq 401) {
    $httpDetail += '（需要 token，检查 otelstore.token 与 X-Otel-Store-Token 头）'
}
Add-Check 'http_200' '返回 200' ($response.Status -eq 200) $httpDetail

$summary = $null
try {
    $summary = $response.Body | ConvertFrom-Json -Depth 20
} catch {
    $summary = $null
}
Add-Check 'json_parse' '体是合法 JSON' ($null -ne $summary) "bytes=$($response.Body.Length)"

if ($null -eq $summary) {
    $passCount = 0
    $total = $checks.Count
} else {
    $present = @($summary.PSObject.Properties.Name)
    $requiredKeys = @('config', 'startedAt', 'uptimeMs', 'agentVersion', 'storeDegradedReason', 'queues', 'store')
    $missing = @($requiredKeys | Where-Object { $present -notcontains $_ })
    $keysDetail = if ($missing.Count -eq 0) { 'all present' } else { 'missing=' + ($missing -join ',') }
    Add-Check 'keys' '顶层键齐全' ($missing.Count -eq 0) $keysDetail

    $store = $summary.store
    Add-Check 'store_present' 'store 非空' ($null -ne $store) $(if ($null -eq $store) { 'store=null' } else { 'ok' })

    $rowsOk = $false
    $rowsDetail = 'store=null'
    if ($null -ne $store) {
        $rowValues = @($store.spanRows, $store.logRows, $store.metricRows)
        $rowsOk = @($rowValues | Where-Object { $null -ne $_ -and [double]$_ -ge 0 }).Count -eq 3
        $rowsDetail = "spans=$($rowValues[0]) logs=$($rowValues[1]) metrics=$($rowValues[2])"
    }
    Add-Check 'rows_nonneg' '三个行数非负' $rowsOk $rowsDetail

    $evictedOk = $false
    if ($null -ne $store -and $null -ne $store.evicted) {
        $ev = $store.evicted
        $evictedOk = $null -ne $ev.spans -and $null -ne $ev.logs -and $null -ne $ev.metrics
    }
    Add-Check 'evicted_shape' 'evicted 三分项齐全' $evictedOk $(if ($evictedOk) { "spans=$($store.evicted.spans) logs=$($store.evicted.logs) metrics=$($store.evicted.metrics)" } else { 'evicted 缺失或结构变了' })

    $storeErrors = if ($null -ne $store) { $store.storeErrors } else { $null }
    $errorsOk = $null -ne $storeErrors -and [long]$storeErrors -eq 0
    Add-Check 'store_errors' 'storeErrors 为 0' $errorsOk "storeErrors=$storeErrors"

    $degraded = $summary.storeDegradedReason
    $degradedOk = ($present -contains 'storeDegradedReason') -and ($null -eq $degraded)
    Add-Check 'degraded_null' '没有降级原因' $degradedOk $(if ($null -eq $degraded) { 'null' } else { "reason=$degraded" })

    $tokenShown = if ($null -ne $summary.config) { [string]$summary.config.token } else { '' }
    $tokenMaskedOk = $tokenShown -eq '***' -or $tokenShown -eq '<generated-at-startup>'
    $tokenDetail = if ($tokenMaskedOk) { $tokenShown } else { "unexpected token field, length=$($tokenShown.Length)" }
    Add-Check 'token_masked' 'token 只出现掩码' $tokenMaskedOk $tokenDetail

    if ($Token) {
        $echoed = $response.Body.Contains($Token)
        Add-Check 'token_not_echoed' '响应体不含 token' (-not $echoed) $(if ($echoed) { 'BODY ECHOES TOKEN' } else { 'not present' })
    }

    $agent = [string]$summary.agentVersion
    $agentOk = $agent -match 'opentelemetry'
    Add-Check 'agent_version' 'agentVersion 指向 agent' $agentOk $(if ($agent) { $agent } else { 'null，说明这个 JVM 没挂 agent' })

    $expectedPort = ([uri]$BaseUrl).Port
    $actualPort = $summary.actualPort
    $portOk = $null -ne $actualPort -and [int]$actualPort -eq $expectedPort
    Add-Check 'actual_port' 'actualPort 与请求端口一致' $portOk "expected=$expectedPort actual=$actualPort"

    $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $startedMs = [long]$summary.startedAt
    $uptimeMs = [long]$summary.uptimeMs
    $driftMs = [math]::Abs(($startedMs + $uptimeMs) - $nowMs)
    Add-Check 'uptime' 'startedAt + uptimeMs 贴近当前时间' ($driftMs -le 5000) "driftMs=$driftMs uptimeMs=$uptimeMs"

    $passCount = @($checks | Where-Object { $_.pass }).Count
    $total = $checks.Count
}

$ok = $passCount -eq $total

if ($Json) {
    [pscustomobject]@{
        ok       = $ok
        endpoint = $url
        dataDir  = $resolvedDataDir
        pass     = $passCount
        total    = $total
        checks   = $checks
    } | ConvertTo-Json -Depth 6
} else {
    "assert-readout $Command  $url"
    foreach ($check in $checks) {
        $mark = if ($check.pass) { 'ok  ' } else { 'FAIL' }
        '{0} {1,-16} {2}' -f $mark, $check.id, $check.detail
    }
    "PASS $passCount/$total"
}

$exitCode = if ($ok) { 0 } else { 1 }
exit $exitCode
