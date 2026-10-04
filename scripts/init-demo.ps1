[CmdletBinding()]
param([switch]$RetryFailed)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-demo/LocalDemo.Common.ps1')
Add-Type -AssemblyName System.Net.Http

function Save-PolicyState {
    param([hashtable]$State, [string]$Path)
    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    [System.IO.File]::WriteAllText($Path, ($State | ConvertTo-Json -Depth 10), [System.Text.UTF8Encoding]::new($false))
}

function Invoke-PolicyApi {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Method,
        [string]$Path,
        [string]$Token,
        [System.Net.Http.HttpContent]$Content
    )
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Path)
    if ($Token) { $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token) }
    if ($Content) { $request.Content = $Content }
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode) {
                throw "演示 API 返回 HTTP $([int]$response.StatusCode)。不会自动重试上传请求。"
            }
            if ([string]::IsNullOrWhiteSpace($body)) { return $null }
            $envelope = $body | ConvertFrom-Json
            if ($envelope.code -ne 'OK') { throw "演示 API 返回业务错误 $($envelope.code)。不会自动重试上传请求。" }
            return $envelope.data
        }
        finally { $response.Dispose() }
    }
    catch [System.Net.Http.HttpRequestException] {
        throw '无法连接本机演示 API。确认先运行 scripts/start.ps1 并等待服务就绪。上传请求不会自动重试。'
    }
    finally { $request.Dispose() }
}

function New-PolicyMultipart {
    param([string]$FilePath, [string]$Title, [string]$Category, [string]$EffectiveFrom)
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    foreach ($entry in @(
            @{ Name = 'title'; Value = $Title },
            @{ Name = 'categoryCode'; Value = $Category },
            @{ Name = 'effectiveFrom'; Value = $EffectiveFrom })) {
        $multipart.Add([System.Net.Http.StringContent]::new($entry.Value, [System.Text.Encoding]::UTF8), $entry.Name)
    }
    $content = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($FilePath))
    $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('text/markdown')
    $multipart.Add($content, 'file', [System.IO.Path]::GetFileName($FilePath))
    return ,$multipart
}

try {
    $envValues = Ensure-LocalDemoEnv
    Assert-LocalDemoDockerEngine
    Assert-LocalDemoComposeConfig
    $port = Get-LocalDemoWebPort
    $policiesDirectory = Join-Path (Get-LocalDemoRoot) 'docs/demo/policies'
    if (Test-LocalDemoBundle) { $policiesDirectory = Join-Path (Get-LocalDemoRoot) 'docs/demo/policies' }
    if (-not (Test-Path -LiteralPath $policiesDirectory -PathType Container)) {
        throw '镜像包缺少三份演示政策文件。'
    }
    $policyFiles = @(
        'refund-and-return-v1.md', 'logistics-service-v1.md', 'exchange-and-repair-v1.md'
    ) | ForEach-Object { Join-Path $policiesDirectory $_ }
    foreach ($filePath in $policyFiles) {
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) { throw "政策样例文件缺失：$(Split-Path -Leaf $filePath)" }
    }

    $statePath = Join-Path (Get-LocalDemoRoot) 'logs/local-demo-policy-state.json'
    $state = @{ schemaVersion = 1; merchantCode = 'DEMO_MERCHANT'; policies = @() }
    if (Test-Path -LiteralPath $statePath -PathType Leaf) {
        try { $loaded = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json }
        catch { throw '本地政策初始化状态文件无法读取；为避免重复 embedding，请先检查后再继续。' }
        if ($loaded.schemaVersion -ne 1 -or $loaded.merchantCode -ne 'DEMO_MERCHANT') {
            throw '本地政策初始化状态文件版本不匹配。'
        }
        $state = @{ schemaVersion = 1; merchantCode = 'DEMO_MERCHANT'; policies = @($loaded.policies) }
    }

    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(30)
    $client.BaseAddress = [Uri]::new("http://127.0.0.1:$port/api/v1/")
    try {
        $loginJson = @{ merchantCode = 'DEMO_MERCHANT'; username = 'admin'; password = [string]$envValues['DEMO_ADMIN_PASSWORD'] } |
            ConvertTo-Json -Compress
        $loginContent = [System.Net.Http.StringContent]::new($loginJson, [System.Text.Encoding]::UTF8, 'application/json')
        $login = Invoke-PolicyApi -Client $client -Method 'POST' -Path 'auth/login' -Content $loginContent
        if (-not $login.accessToken) { throw '管理员登录未返回访问令牌。请检查 .env 中的演示管理员配置。' }
        $token = [string]$login.accessToken

        foreach ($filePath in $policyFiles) {
            $fileName = Split-Path -Leaf $filePath
            $content = [System.IO.File]::ReadAllText($filePath, [System.Text.Encoding]::UTF8)
            $titleMatch = [regex]::Match($content, '(?m)^#\s+(.+?)\s*$')
            $categoryMatch = [regex]::Match($content, '(?m)^政策类别：\s*(\S+)')
            $effectiveMatch = [regex]::Match($content, '(?m)^生效日期：\s*(\d{4}-\d{2}-\d{2})')
            if (-not $titleMatch.Success -or -not $categoryMatch.Success -or -not $effectiveMatch.Success) {
                throw "政策样例元数据不完整：$fileName"
            }
            $hash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
            $effectiveFrom = [DateTimeOffset]::ParseExact($effectiveMatch.Groups[1].Value, 'yyyy-MM-dd',
                [System.Globalization.CultureInfo]::InvariantCulture).ToUniversalTime().ToString('o')
            $record = @($state.policies | Where-Object { $_.fileName -eq $fileName } | Select-Object -First 1)
            if ($record.Count -gt 0) { $record = $record[0] } else { $record = $null }

            if ($record -and $record.contentSha256 -ne $hash) {
                throw "$fileName 与上次入库文件内容不同。为避免覆盖发布政策，请先人工审查版本变更。"
            }
            if ($record -and $record.stage -eq 'UPLOAD_PENDING' -and -not $record.versionId) {
                throw "$fileName 上次上传结果不确定，脚本不会重复创建文档或重复计费。先在政策页面检查上传结果，再根据结果人工处理状态文件。"
            }
            if (-not $record) {
                $record = [pscustomobject]@{
                    fileName = $fileName; title = $titleMatch.Groups[1].Value.Trim()
                    categoryCode = $categoryMatch.Groups[1].Value.Trim(); effectiveFrom = $effectiveFrom
                    contentSha256 = $hash; stage = 'UPLOAD_PENDING'; documentId = $null; versionId = $null
                }
                $state.policies = @($state.policies) + $record
                Save-PolicyState -State $state -Path $statePath
                $multipart = New-PolicyMultipart -FilePath $filePath -Title $record.title -Category $record.categoryCode -EffectiveFrom $effectiveFrom
                try {
                    $version = Invoke-PolicyApi -Client $client -Method 'POST' -Path 'knowledge/documents' -Token $token -Content $multipart
                }
                finally { $multipart.Dispose() }
                $record.documentId = [string]$version.documentId
                $record.versionId = [string]$version.versionId
                $record.stage = 'UPLOADED'
                Save-PolicyState -State $state -Path $statePath
                Write-Host "已提交政策入库任务：$fileName（不会重复提交该版本）。"
            }

            $versionPath = "knowledge/versions/$($record.versionId)"
            $version = Invoke-PolicyApi -Client $client -Method 'GET' -Path $versionPath -Token $token
            if ($version.status -eq 'FAILED') {
                if (-not $RetryFailed) {
                    throw "$fileName 的向量处理失败。脚本不会自动重试或再次计费；确认失败原因和剩余额度后，可显式运行 .\scripts\init-demo.ps1 -RetryFailed。"
                }
                $multipart = New-PolicyMultipart -FilePath $filePath -Title $record.title -Category $record.categoryCode -EffectiveFrom $effectiveFrom
                try {
                    $version = Invoke-PolicyApi -Client $client -Method 'POST' -Path "knowledge/documents/$($record.documentId)/versions" -Token $token -Content $multipart
                }
                finally { $multipart.Dispose() }
                $record.versionId = [string]$version.versionId
                $record.stage = 'UPLOADED'
                $versionPath = "knowledge/versions/$($record.versionId)"
                Save-PolicyState -State $state -Path $statePath
            }

            $deadline = [DateTime]::UtcNow.AddSeconds(180)
            while ($version.status -in @('UPLOADED', 'PROCESSING') -and [DateTime]::UtcNow -lt $deadline) {
                Start-Sleep -Seconds 3
                $version = Invoke-PolicyApi -Client $client -Method 'GET' -Path $versionPath -Token $token
            }
            if ($version.status -eq 'FAILED') { throw "$fileName 向量处理失败。状态和版本 ID 已保存；排查后可重新运行 init-demo.ps1。" }
            if ($version.status -eq 'PUBLISHED') {
                $record.stage = 'PUBLISHED'
                Save-PolicyState -State $state -Path $statePath
                Write-Host "$fileName 已发布；重复运行将只校验现有版本。"
                continue
            }
            if ($version.status -ne 'READY') {
                throw "$fileName 在 180 秒内未进入 READY 状态（当前：$($version.status)）。重跑脚本会先查询此版本，不会重新创建文档。"
            }

            $record.stage = 'PUBLISH_PENDING'
            Save-PolicyState -State $state -Path $statePath
            $null = Invoke-PolicyApi -Client $client -Method 'POST' -Path "knowledge/versions/$($record.versionId)/publish" -Token $token
            $verified = Invoke-PolicyApi -Client $client -Method 'GET' -Path $versionPath -Token $token
            if ($verified.status -ne 'PUBLISHED') { throw "$fileName 发布状态未通过确认。重跑脚本会检查同一版本。" }
            $record.stage = 'PUBLISHED'
            Save-PolicyState -State $state -Path $statePath
            Write-Host "$fileName 已完成入库和发布。"
        }
    }
    finally { $client.Dispose() }

    Write-Host "三份样例政策均已确认发布。状态记录保存在 $statePath；未保存令牌或模型密钥。"
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
