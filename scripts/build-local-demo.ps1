[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-demo/LocalDemo.Common.ps1')

try {
    if (Test-LocalDemoBundle) {
        throw '镜像包不含应用源码，不能从该目录重新构建。请在源码项目中运行此命令，或重新导出镜像包。'
    }
    $envValues = Ensure-LocalDemoEnv
    Assert-LocalDemoDockerEngine
    Assert-LocalDemoComposeConfig

    $fingerprintBefore = Get-LocalDemoSourceFingerprint
    $imageMap = @(
        @{ Service = 'postgres'; Source = 'pgvector/pgvector:pg16'; Alias = 'aftersales-local-demo/postgres:local' },
        @{ Service = 'redis'; Source = 'redis:7.4-alpine'; Alias = 'aftersales-local-demo/redis:local' }
    )
    foreach ($image in $imageMap) {
        Write-Host "准备固定 $($image.Service) 镜像版本…"
        $null = Invoke-LocalDemoDocker -Arguments @('pull', $image.Source)
        $null = Invoke-LocalDemoDocker -Arguments @('tag', $image.Source, $image.Alias)
    }

    Write-Host '使用本机 Maven Wrapper 将当前后端源码打包为可运行 JAR…'
    $root = Get-LocalDemoRoot
    $mavenWrapper = Join-Path $root 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
        throw '源码项目缺少 Maven Wrapper，无法生成当前后端 JAR。'
    }
    Push-Location $root
    try {
        & $mavenWrapper '-pl' 'backend' '-am' '-DskipTests' 'package'
        if ($LASTEXITCODE -ne 0) { throw 'Maven Wrapper 未能生成后端 JAR。' }
    }
    finally { Pop-Location }
    $backendJar = Join-Path $root 'backend/target/aftersales-agent.jar'
    if (-not (Test-Path -LiteralPath $backendJar -PathType Leaf)) {
        throw 'Maven Wrapper 未生成 backend/target/aftersales-agent.jar。'
    }

    Write-Host '使用当前后端 JAR 与前端源码构建 backend 和 web 镜像…'
    $null = Invoke-LocalDemoBuild -ComposeArguments @('build', 'backend', 'web')
    $fingerprintAfter = Get-LocalDemoSourceFingerprint
    if ($fingerprintBefore -ne $fingerprintAfter) {
        throw '构建期间源码发生变化，未写入镜像清单。请确认文件稳定后重跑 scripts/build-local-demo.ps1。'
    }

    $records = foreach ($descriptor in Get-LocalDemoImageDescriptors) {
        $id = Get-LocalDemoImageId -Image $descriptor.Tag
        if (-not $id) { throw "构建后找不到 $($descriptor.Service) 镜像。" }
        $sourceDigest = $null
        if ($descriptor.Source) {
            $repoDigestsRaw = Invoke-LocalDemoDocker -Arguments @('image', 'inspect', '--format', '{{json .RepoDigests}}', $descriptor.Tag)
            $repoDigests = @($repoDigestsRaw -join '' | ConvertFrom-Json)
            $sourceBase = $descriptor.Source.Substring(0, $descriptor.Source.LastIndexOf(':'))
            $sourceDigest = $repoDigests | Where-Object { $_.StartsWith("$sourceBase@sha256:") } | Select-Object -First 1
            if (-not $sourceDigest) {
                throw "无法记录 $($descriptor.Service) 上游仓库摘要，未生成可审计清单。"
            }
        }
        [pscustomobject]@{
            service = $descriptor.Service
            tag = $descriptor.Tag
            imageId = $id
            sourceReference = $descriptor.Source
            sourceRepoDigest = $sourceDigest
        }
    }

    $outputDirectory = Join-Path (Get-LocalDemoRoot) 'dist/local-demo'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $manifest = [ordered]@{
        schemaVersion = 1
        project = Get-LocalDemoProjectName
        builtAtUtc = [DateTime]::UtcNow.ToString('o')
        sourceFingerprint = $fingerprintAfter
        images = @($records)
    }
    $manifestPath = Join-Path $outputDirectory 'build-manifest.json'
    [System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    Write-Host "镜像构建完成。四项 Compose 镜像 ID 已记录到 $manifestPath。"
    Write-Host '本脚本未启动或停止容器，也未调用模型 API。'
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
