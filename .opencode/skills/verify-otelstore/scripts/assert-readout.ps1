#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('summary', 'histogram', 'self-log')]
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

$checks = [System.Collections.Generic.List[object]]::new()

function Add-Check {
    param([string]$Id, [string]$Title, [bool]$Pass, [string]$Detail)
    $checks.Add([pscustomobject]@{ id = $Id; title = $Title; pass = $Pass; detail = $Detail })
}

function Invoke-Readout {
    param([string]$Url)

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

function Get-Json {
    param([string]$Url)

    $response = Invoke-Readout -Url $Url
    if ($response.ExitCode -ne 0) {
        Stop-WithUsageError "连不上 $Url（curl 退出码 $($response.ExitCode)）：$($response.Error)"
    }

    $parsed = $null
    try {
        $parsed = $response.Body | ConvertFrom-Json -Depth 30
    } catch {
        $parsed = $null
    }

    [pscustomobject]@{
        Status = $response.Status
        Body   = $response.Body
        Parsed = $parsed
    }
}

function Add-FetchChecks {
    param($Fetched, [string]$Prefix = '')

    $httpId = if ($Prefix) { "${Prefix}_http" } else { 'http_200' }
    $httpDetail = "status=$($Fetched.Status)"
    if ($Fetched.Status -eq 401) {
        $httpDetail += '（需要 token，检查 otelstore.token 与 X-Otel-Store-Token 头）'
    }
    Add-Check $httpId '返回 200' ($Fetched.Status -eq 200) $httpDetail

    $jsonId = if ($Prefix) { "${Prefix}_json" } else { 'json_parse' }
    $parsed = $null -ne $Fetched.Parsed
    Add-Check $jsonId '体是合法 JSON' $parsed "bytes=$($Fetched.Body.Length)"

    return $parsed
}

function Test-NonDecreasing {
    param($Values)
    $items = @($Values)
    for ($i = 1; $i -lt $items.Count; $i++) {
        if ([double]$items[$i] -lt [double]$items[$i - 1]) { return $false }
    }
    return $true
}

function Invoke-SummaryChecks {
    $url = "$BaseUrl/api/summary"
    $fetched = Get-Json $url
    if (-not (Add-FetchChecks $fetched)) { return $url }

    $summary = $fetched.Parsed
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
        $echoed = $fetched.Body.Contains($Token)
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

    return $url
}

function Invoke-HistogramChecks {
    $url = "$BaseUrl/api/metrics?limit=2000"
    $fetched = Get-Json $url
    if (-not (Add-FetchChecks $fetched)) { return $url }

    $points = @($fetched.Parsed) | Where-Object { $null -ne $_ }
    $histograms = @($points | Where-Object { $_.dataType -eq 'HISTOGRAM' })
    $explicit = @($histograms | Where-Object { $_.detail.flavor -eq 'explicit' })
    $unknown = @($points | Where-Object { $_.detail.flavor -eq 'unknown' })

    Add-Check 'has_histograms' '至少一条直方图点' ($histograms.Count -gt 0) "histograms=$($histograms.Count) explicit=$($explicit.Count)"

    $unknownDetail = if ($unknown.Count -eq 0) { '0 unknown' } else { "unknown=$($unknown.Count)，首个：$(($unknown | Select-Object -First 1).metricName)" }
    Add-Check 'no_unknown' '没有认不出的 detail' ($unknown.Count -eq 0) $unknownDetail

    $shapeBad = @()
    $cumBad = @()
    $totalBad = @()
    foreach ($point in $explicit) {
        $detail = $point.detail
        $le = @($detail.leBoundaries)
        $counts = @($detail.counts)
        $cumulative = @($detail.cumulative)
        $name = [string]$point.metricName

        $shapeOk = $false
        if ($le.Count -gt 0 -and $le.Count -eq $counts.Count -and $le.Count -eq $cumulative.Count -and $le[-1] -eq '+Inf') {
            $numericLe = @()
            if ($le.Count -gt 1) {
                $numericLe = @($le[0..($le.Count - 2)] | ForEach-Object { [double]$_ })
            }
            $shapeOk = Test-NonDecreasing $numericLe
        }
        if (-not $shapeOk) { $shapeBad += "$name(le=$($le.Count) counts=$($counts.Count) cum=$($cumulative.Count) last=$($le[-1]))" }

        $nonNegative = @($counts | Where-Object { [long]$_ -lt 0 }).Count -eq 0
        if (-not ($nonNegative -and (Test-NonDecreasing ($cumulative | ForEach-Object { [long]$_ })))) {
            $cumBad += $name
        }

        if ($null -ne $point.metricCount -and $cumulative.Count -gt 0 -and [long]$cumulative[-1] -ne [long]$point.metricCount) {
            $totalBad += "$name(cum=$($cumulative[-1]) count=$($point.metricCount))"
        }
    }

    $shapeDetail = if ($explicit.Count -eq 0) { '没有 explicit 直方图，先 POST /demo/metrics 造一条' } elseif ($shapeBad.Count -eq 0) { "checked=$($explicit.Count)" } else { 'bad=' + ($shapeBad -join '; ') }
    Add-Check 'explicit_shape' '桶数与边界对得上' ($explicit.Count -gt 0 -and $shapeBad.Count -eq 0) $shapeDetail

    $cumDetail = if ($explicit.Count -eq 0) { '没有 explicit 直方图' } elseif ($cumBad.Count -eq 0) { "checked=$($explicit.Count)" } else { 'bad=' + ($cumBad -join ',') }
    Add-Check 'cumulative_monotonic' '累计计数单调不减' ($explicit.Count -gt 0 -and $cumBad.Count -eq 0) $cumDetail

    $totalDetail = if ($explicit.Count -eq 0) { '没有 explicit 直方图' } elseif ($totalBad.Count -eq 0) { "checked=$($explicit.Count)" } else { 'bad=' + ($totalBad -join '; ') }
    Add-Check 'total_matches' '末桶累计等于 metricCount' ($explicit.Count -gt 0 -and $totalBad.Count -eq 0) $totalDetail

    return $url
}

function Invoke-SelfLogChecks {
    $url = "$BaseUrl/api/self-log?lines=5"
    $fetched = Get-Json $url
    if (-not (Add-FetchChecks $fetched)) { return $url }

    $payload = $fetched.Parsed
    $lines = @($payload.lines) | Where-Object { $null -ne $_ }
    $note = [string]$payload.note
    $file = [string]$payload.file

    $shapeOk = $null -ne $payload.lines -and $file -eq 'otelstore.log' -and $null -ne $payload.note
    Add-Check 'shape' 'lines / file / note 齐全' $shapeOk "file=$file note=[$note]"

    Add-Check 'line_count' '行数不超过请求值' ($lines.Count -le 5) "lines=$($lines.Count) requested=5"

    $badFormat = @($lines | Where-Object { $_ -notmatch '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} (INFO|WARN|ERROR) ' })
    Add-Check 'line_format' '每行是扩展自己的格式' ($badFormat.Count -eq 0) $(if ($badFormat.Count -eq 0) { "checked=$($lines.Count)" } else { "bad=$($badFormat.Count)" })

    $fromUs = @($lines | Where-Object { ([string]$_).Contains('[otel-local-telemetry-store]') })
    Add-Check 'origin' '行里有本扩展的标识' ($fromUs.Count -gt 0) "matched=$($fromUs.Count)/$($lines.Count)"

    $noteOk = ($lines.Count -eq 0) -eq ($note.Length -gt 0)
    Add-Check 'note_consistency' '空行数与 note 互斥' $noteOk "lines=$($lines.Count) noteLen=$($note.Length)"

    $clampUrl = "$BaseUrl/api/self-log?lines=100000"
    $clamped = Get-Json $clampUrl
    $clampLines = @($clamped.Parsed.lines) | Where-Object { $null -ne $_ }
    Add-Check 'clamp_http' '封顶请求返回 200' ($clamped.Status -eq 200) "status=$($clamped.Status)"
    Add-Check 'clamp_limit' '封顶不超过 500 行' ($clamped.Status -eq 200 -and $clampLines.Count -le 500) "lines=$($clampLines.Count) cap=500"

    $badUrl = "$BaseUrl/api/self-log?lines=abc"
    $fallback = Get-Json $badUrl
    $fallbackLines = @($fallback.Parsed.lines) | Where-Object { $null -ne $_ }
    Add-Check 'bad_param_fallback' '非数字参数回落缺省 100 行' ($fallback.Status -eq 200 -and $fallbackLines.Count -le 100) "status=$($fallback.Status) lines=$($fallbackLines.Count)"

    return $url
}

$url = switch ($Command) {
    'summary' { Invoke-SummaryChecks }
    'histogram' { Invoke-HistogramChecks }
    'self-log' { Invoke-SelfLogChecks }
}

$passCount = @($checks | Where-Object { $_.pass }).Count
$total = $checks.Count
$ok = $passCount -eq $total

if ($Json) {
    [pscustomobject]@{
        ok       = $ok
        command  = $Command
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
        '{0} {1,-20} {2}' -f $mark, $check.id, $check.detail
    }
    "PASS $passCount/$total"
}

$exitCode = if ($ok) { 0 } else { 1 }
exit $exitCode
