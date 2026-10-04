[CmdletBinding()]
param([string]$PackageDirectory)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-demo/LocalDemo.Common.ps1')

try {
    Assert-LocalDemoDockerEngine
    if ([string]::IsNullOrWhiteSpace($PackageDirectory)) {
        if (Test-LocalDemoBundle) { $PackageDirectory = Get-LocalDemoRoot }
        else { $PackageDirectory = Join-Path (Get-LocalDemoRoot) 'dist/local-demo' }
    }
    $packageRoot = (Resolve-Path -LiteralPath $PackageDirectory -ErrorAction Stop).Path
    $manifestPath = Join-Path $packageRoot 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw '未找到 manifest.json。请指定完整解压后的镜像包目录。'
    }
    try { $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json }
    catch { throw 'manifest.json 格式无效。' }
    if ($manifest.project -ne (Get-LocalDemoProjectName) -or $manifest.schemaVersion -ne 1) {
        throw '镜像清单项目名或版本不匹配。'
    }
    foreach ($file in $manifest.files) {
        $relative = [string]$file.path
        if ([System.IO.Path]::IsPathRooted($relative) -or $relative -match '(^|[\\/])\.\.([\\/]|$)') {
            throw '镜像清单包含越界文件路径，拒绝导入。'
        }
        $path = Join-Path $packageRoot $relative
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "镜像包文件缺失：$relative" }
        $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $file.sha256) { throw "镜像包文件校验失败：$relative" }
    }
    $archiveName = [string]$manifest.archive
    if ([System.IO.Path]::IsPathRooted($archiveName) -or $archiveName -match '[\\/]') {
        throw '镜像清单中的归档文件名无效。'
    }
    $archivePath = Join-Path $packageRoot $archiveName
    if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) { throw "镜像归档缺失：$archiveName" }
    $actualArchiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualArchiveHash -ne [string]$manifest.archiveSha256) {
        throw '镜像归档 SHA-256 校验失败，未加载镜像。'
    }
    $sidecarPath = "$archivePath.sha256"
    if (-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf)) { throw '镜像归档 SHA-256 sidecar 文件缺失。' }
    $sidecarText = [System.IO.File]::ReadAllText($sidecarPath).Trim()
    if ($sidecarText -notmatch ('^' + [regex]::Escape($actualArchiveHash) + '\s+\*?' + [regex]::Escape($archiveName) + '$')) {
        throw '镜像归档 SHA-256 sidecar 校验失败。'
    }
    $expectedImages = @(Get-LocalDemoImageDescriptors)
    if (@($manifest.images).Count -ne $expectedImages.Count) { throw '镜像清单未包含完整四项服务镜像。' }
    foreach ($descriptor in $expectedImages) {
        $matches = @($manifest.images | Where-Object { $_.service -eq $descriptor.Service -and $_.tag -eq $descriptor.Tag })
        if ($matches.Count -ne 1 -or [string]$matches[0].imageId -notmatch '^sha256:[a-f0-9]{64}$') {
            throw "镜像清单中 $($descriptor.Service) 的固定 tag 或 image ID 无效。"
        }
    }

    Write-Host '归档与包内文件校验通过，正在导入四项服务镜像…'
    $null = Invoke-LocalDemoDocker -Arguments @('load', '-i', $archivePath)
    foreach ($record in $manifest.images) {
        $actualId = Get-LocalDemoImageId -Image ([string]$record.tag)
        if ($actualId -ne [string]$record.imageId) {
            throw "导入后的 $($record.service) image ID 与清单不符。"
        }
    }
    Write-Host '四项镜像均已导入并通过 image ID 校验。接下来配置本机 .env，再运行 .\scripts\start.ps1。'
    Write-Host '本脚本未启动或停止容器，也未调用模型 API。'
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
