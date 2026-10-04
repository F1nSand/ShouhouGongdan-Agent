[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-demo/LocalDemo.Common.ps1')

try {
    if (Test-LocalDemoBundle) { throw '请从完整源码项目运行导出脚本；镜像包目录不含构建源码。' }
    $null = Ensure-LocalDemoEnv
    Assert-LocalDemoDockerEngine
    Assert-LocalDemoComposeConfig
    & (Join-Path (Get-LocalDemoRoot) 'scripts/build-local-demo.ps1')
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw '镜像构建失败，未导出镜像包。' }

    $root = Get-LocalDemoRoot
    $outputDirectory = Join-Path $root 'dist/local-demo'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $archiveName = 'aftersales-local-demo-images.tar'
    $archivePath = Join-Path $outputDirectory $archiveName
    $temporaryArchive = Join-Path $outputDirectory 'aftersales-local-demo-images.pending.tar'
    $allowedPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($managed in @(
            'build-manifest.json', 'manifest.json', 'QUICKSTART.txt', $archiveName,
            "$archiveName.sha256", 'aftersales-local-demo-images.pending.tar',
            '.env.example', 'infra/compose.local-demo.yaml', 'infra/postgres/01-create-runtime-role.sh',
            'infra/nginx/default.conf', 'backend/Dockerfile', 'frontend/Dockerfile',
            'scripts/New-LocalEnv.ps1', 'scripts/start.ps1', 'scripts/stop.ps1', 'scripts/status.ps1',
            'scripts/init-demo.ps1', 'scripts/reset-demo.ps1', 'scripts/build-local-demo.ps1',
            'scripts/export-local-demo.ps1', 'scripts/import-local-demo.ps1',
            'scripts/local-demo/LocalDemo.Common.ps1',
            'docs/demo/policies/refund-and-return-v1.md', 'docs/demo/policies/logistics-service-v1.md',
            'docs/demo/policies/exchange-and-repair-v1.md')) {
        [void]$allowedPaths.Add($managed.Replace('\\', '/'))
    }
    $unexpected = @(Get-ChildItem -LiteralPath $outputDirectory -File -Recurse -Force |
        ForEach-Object { $_.FullName.Substring($outputDirectory.Length).TrimStart([char[]]@(92, 47)) -replace '\\', '/' } |
        Where-Object { -not $allowedPaths.Contains($_) })
    if ($unexpected.Count -gt 0) {
        throw 'dist/local-demo 中存在非发布文件（可能含本机私有数据）。请先移出这些文件后重新导出；未删除任何文件。'
    }
    $images = @(Get-LocalDemoImageDescriptors)
    $null = Invoke-LocalDemoDocker -Arguments (@('save', '-o', $temporaryArchive) + @($images | ForEach-Object { $_.Tag }))

    $imageRecords = foreach ($descriptor in $images) {
        $id = Get-LocalDemoImageId -Image $descriptor.Tag
        if (-not $id) { throw "导出前找不到 $($descriptor.Service) 镜像。" }
        $sourceDigest = $null
        if ($descriptor.Source) {
            $raw = Invoke-LocalDemoDocker -Arguments @('image', 'inspect', '--format', '{{json .RepoDigests}}', $descriptor.Tag)
            $digests = @($raw -join '' | ConvertFrom-Json)
            $base = $descriptor.Source.Substring(0, $descriptor.Source.LastIndexOf(':'))
            $sourceDigest = $digests | Where-Object { $_.StartsWith("$base@sha256:") } | Select-Object -First 1
            if (-not $sourceDigest) { throw "未能记录 $($descriptor.Service) 的上游仓库摘要。" }
        }
        [pscustomobject]@{
            service = $descriptor.Service
            tag = $descriptor.Tag
            imageId = $id
            sourceReference = $descriptor.Source
            sourceRepoDigest = $sourceDigest
        }
    }

    $packageFiles = @(
        '.env.example',
        'infra/compose.local-demo.yaml',
        'infra/postgres/01-create-runtime-role.sh',
        'infra/nginx/default.conf',
        'backend/Dockerfile', 'frontend/Dockerfile',
        'scripts/New-LocalEnv.ps1',
        'scripts/start.ps1', 'scripts/stop.ps1', 'scripts/status.ps1', 'scripts/init-demo.ps1',
        'scripts/reset-demo.ps1', 'scripts/build-local-demo.ps1', 'scripts/export-local-demo.ps1',
        'scripts/import-local-demo.ps1', 'scripts/local-demo/LocalDemo.Common.ps1',
        'docs/demo/policies/refund-and-return-v1.md',
        'docs/demo/policies/logistics-service-v1.md',
        'docs/demo/policies/exchange-and-repair-v1.md'
    )
    foreach ($relative in $packageFiles) {
        $sourcePath = Join-Path $root $relative
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "打包所需文件缺失：$relative"
        }
        $destination = Join-Path $outputDirectory $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $sourcePath -Destination $destination -Force
    }

    $sha = (Get-FileHash -LiteralPath $temporaryArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    Move-Item -LiteralPath $temporaryArchive -Destination $archivePath -Force
    $buildManifestPath = Join-Path $outputDirectory 'build-manifest.json'
    $buildManifest = if (Test-Path -LiteralPath $buildManifestPath -PathType Leaf) {
        Get-Content -LiteralPath $buildManifestPath -Raw | ConvertFrom-Json
    } else { $null }
    $manifest = [ordered]@{
        schemaVersion = 1
        project = Get-LocalDemoProjectName
        releaseId = if ($buildManifest -and $buildManifest.sourceFingerprint) { "local-$($buildManifest.sourceFingerprint.Substring(0, 12))" } else { 'local' }
        builtAtUtc = if ($buildManifest) { $buildManifest.builtAtUtc } else { $null }
        packagedAtUtc = [DateTime]::UtcNow.ToString('o')
        sourceFingerprint = if ($buildManifest) { $buildManifest.sourceFingerprint } else { $null }
        archive = $archiveName
        archiveSha256 = $sha
        images = @($imageRecords)
        files = @($files)
    }
    $manifestPath = Join-Path $outputDirectory 'manifest.json'
    [System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText((Join-Path $outputDirectory "$archiveName.sha256"), "$sha *$archiveName`n", [System.Text.UTF8Encoding]::new($false))

    $quickStart = @'
本地售后演示镜像包

1. 安装并启动 Docker Desktop，切换到 Linux 容器。
2. 将本目录复制到目标电脑。首次运行 .\scripts\start.ps1 会生成本机 .env；填写 DEEPSEEK_API_KEY 与 SILICONFLOW_API_KEY 后重试。
3. 在本目录运行 .\scripts\import-local-demo.ps1，然后运行 .\scripts\start.ps1。
4. 打开 http://127.0.0.1:38080。停止使用 .\scripts\stop.ps1，查看状态使用 .\scripts\status.ps1。

本包不含 .env、密钥、数据库导出或用户上传内容。真实模型功能需要网络连接。镜像由 manifest.json 中的 image ID 和 SHA-256 校验。
'@
    $quickStartPath = Join-Path $outputDirectory 'QUICKSTART.txt'
    [System.IO.File]::WriteAllText($quickStartPath, $quickStart, [System.Text.UTF8Encoding]::new($false))
    $files = foreach ($relative in $packageFiles) {
        $path = Join-Path $outputDirectory $relative
        [pscustomobject]@{ path = $relative -replace '\\', '/'; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $files += [pscustomobject]@{ path = 'QUICKSTART.txt'; sha256 = (Get-FileHash -LiteralPath $quickStartPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    $manifest.files = @($files)
    [System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    Write-Host "镜像包已生成：$outputDirectory"
    Write-Host "镜像归档 SHA-256：$sha"
    Write-Host '包内包含四项镜像、运行脚本、无密钥模板和三份合成政策；未包含 .env。'
    Write-Host '本脚本未启动或停止容器，也未调用模型 API。'
}
catch {
    $pendingPath = Join-Path (Get-LocalDemoRoot) 'dist/local-demo/aftersales-local-demo-images.pending.tar'
    if (Test-Path -LiteralPath $pendingPath -PathType Leaf) { Remove-Item -LiteralPath $pendingPath -Force }
    Write-Error $_.Exception.Message
    exit 1
}
