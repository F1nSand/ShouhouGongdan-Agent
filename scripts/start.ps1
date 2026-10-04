[CmdletBinding()]
param([switch]$Build)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-demo/LocalDemo.Common.ps1')

try {
    $envValues = Ensure-LocalDemoEnv
    Assert-LocalDemoDockerEngine
    Assert-LocalDemoComposeConfig
    $port = Get-LocalDemoWebPort
    if (-not (Test-LocalDemoPortAvailable -Port $port)) {
        throw "本机端口 $port 已被其他程序占用。停止占用该端口的程序，或在 .env 中设置 DEMO_WEB_PORT=其他端口 后重试。"
    }

    if ($Build) {
        & (Join-Path (Get-LocalDemoRoot) 'scripts/build-local-demo.ps1')
        if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw '显式镜像构建失败。' }
    }
    try {
        $null = Assert-LocalDemoImagesMatchManifest -RequireManifest
    }
    catch {
        if (-not (Test-LocalDemoBundle) -and ($_.Exception.Message -match '^缺少 (postgres|redis|backend|web) 镜像\.' -or
                $_.Exception.Message -eq '找不到本地镜像清单。请先运行 scripts/build-local-demo.ps1 或从镜像包运行 import-local-demo.ps1。')) {
            Write-Host '首次启动或本机镜像缺失，先根据当前源码构建本地镜像…'
            & (Join-Path (Get-LocalDemoRoot) 'scripts/build-local-demo.ps1')
            if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw '首次镜像构建失败。' }
            $null = Assert-LocalDemoImagesMatchManifest -RequireManifest
        } else { throw }
    }

    Write-Host '启动本地售后演示服务…'
    $null = Invoke-LocalDemoCompose -ComposeArguments @('up', '-d', '--no-build')

    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    while ([DateTime]::UtcNow -lt $deadline) {
        $backendProbe = Invoke-LocalDemoCompose -ComposeArguments @('exec', '-T', 'web', 'wget', '-q', '-O', '-',
            'http://backend:8081/actuator/health/readiness') -Quiet -AllowFailure
        $readinessBody = $backendProbe.Output -join ''
        $backendReady = $backendProbe.ExitCode -eq 0 -and $readinessBody -match '"status"\s*:\s*"UP"'

        $webReady = $false
        if ($backendReady) {
            try {
                $webResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$port/" -UseBasicParsing -TimeoutSec 5
                $webReady = $webResponse.StatusCode -eq 200
            } catch { $webReady = $false }
        }
        if ($backendReady -and $webReady) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) {
        throw '服务在 180 秒内未就绪。数据卷和容器已保留；查看 scripts/status.ps1 与 docker compose logs --tail 60 backend 排查后重试。'
    }

    Write-Host "本地工作台已就绪：http://127.0.0.1:$port"
    Write-Host 'Backend readiness 已从 Docker 内部网络验证；数据库、缓存和后端没有宿主机端口映射。'
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
