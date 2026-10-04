$script:LocalDemoRoot = [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PSCommandPath) '..\..'))
$script:LocalDemoProject = 'aftersales-local-demo'
$script:LocalDemoComposeFile = Join-Path $script:LocalDemoRoot 'infra/compose.local-demo.yaml'
$script:LocalDemoIsBundle = -not (Test-Path -LiteralPath (Join-Path $script:LocalDemoRoot 'backend/src'))

function Get-LocalDemoRoot { return $script:LocalDemoRoot }
function Get-LocalDemoProjectName { return $script:LocalDemoProject }
function Test-LocalDemoBundle { return $script:LocalDemoIsBundle }

function Get-LocalDemoDockerPath {
    $application = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($application) { return $application.Source }

    $candidates = @()
    if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe' }
    if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe' }
    if (${env:ProgramFiles(x86)}) { $candidates += Join-Path ${env:ProgramFiles(x86)} 'Docker/Docker/resources/bin/docker.exe' }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw '未找到 Docker CLI。请安装 Docker Desktop，或将其 docker.exe 放入 PATH。'
}

function Invoke-LocalDemoDocker {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$Quiet,
        [switch]$AllowFailure,
        [switch]$ConfigurationCommand
    )

    $dockerPath = Get-LocalDemoDockerPath
    # Windows PowerShell 5.1 wraps native stderr as error records, including Compose progress.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $dockerPath @Arguments 2>&1)
    }
    finally { $ErrorActionPreference = $previousErrorActionPreference }
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        if ($ConfigurationCommand) {
            throw 'Docker Compose rejected the local demo configuration. Check that .env is complete; values were not printed.'
        }
        $safeText = ($output | ForEach-Object { [string]$_ } |
            Where-Object { $_ -notmatch '(?i)(api.?key|password|secret|token|authorization)\s*[:=]' } |
            Select-Object -Last 5) -join ' '
        if ([string]::IsNullOrWhiteSpace($safeText)) { $safeText = 'No diagnostic text was returned.' }
        throw "Docker command failed (exit $exitCode): $safeText"
    }
    if ($Quiet) { return [pscustomobject]@{ ExitCode = $exitCode; Output = $output } }
    return $output
}

function Get-LocalDemoEnvPath {
    return Join-Path $script:LocalDemoRoot '.env'
}

function Read-LocalDemoEnv {
    param([string]$Path = (Get-LocalDemoEnvPath))
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $values }
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) { continue }
        $key = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$key] = $value
    }
    return $values
}

function Ensure-LocalDemoEnv {
    param([switch]$AllowMissingModelKeys, [switch]$NoGenerate)
    $envPath = Get-LocalDemoEnvPath
    if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
        if ($NoGenerate) { throw '找不到现有 .env。为避免生成与已运行服务不匹配的新凭据，请先恢复原 .env。' }
        $generator = Join-Path $script:LocalDemoRoot 'scripts/New-LocalEnv.ps1'
        if (-not (Test-Path -LiteralPath $generator -PathType Leaf)) {
            throw '缺少 .env 和本地配置生成脚本。请先从源码项目复制 .env.example 为 .env 并补齐本机参数。'
        }
        & $generator
    }
    if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
        throw '未创建 .env。请先准备本机环境配置。'
    }
    $values = Read-LocalDemoEnv -Path $envPath
    if (-not $AllowMissingModelKeys) {
        foreach ($key in @('DEEPSEEK_API_KEY', 'SILICONFLOW_API_KEY')) {
            if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$values[$key])) {
                throw "缺少 $key。请编辑 .env 配置该模型 Key 后重试；Key 值不会显示在终端或日志。"
            }
        }
    }
    return $values
}

function Get-LocalDemoWebPort {
    $values = Read-LocalDemoEnv
    $candidate = if (-not [string]::IsNullOrWhiteSpace($env:DEMO_WEB_PORT)) {
        $env:DEMO_WEB_PORT
    } elseif ($values.ContainsKey('DEMO_WEB_PORT') -and -not [string]::IsNullOrWhiteSpace([string]$values['DEMO_WEB_PORT'])) {
        [string]$values['DEMO_WEB_PORT']
    } else { '38080' }
    $port = 0
    if (-not [int]::TryParse([string]$candidate, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        throw 'DEMO_WEB_PORT 必须是 1–65535 之间的端口号。'
    }
    return $port
}

function Get-LocalDemoComposeArguments {
    param([string]$EnvPath = (Get-LocalDemoEnvPath))
    if (-not (Test-Path -LiteralPath $script:LocalDemoComposeFile -PathType Leaf)) {
        throw '找不到 infra/compose.local-demo.yaml。请从项目根目录运行脚本，或确认镜像包已完整解压。'
    }
    return @('compose', '--env-file', $EnvPath,
        '-f', $script:LocalDemoComposeFile, '-p', $script:LocalDemoProject)
}

function Assert-LocalDemoDockerEngine {
    $result = Invoke-LocalDemoDocker -Arguments @('info', '--format', '{{.OSType}}') -Quiet
    if ($result.ExitCode -ne 0) {
        throw 'Docker Engine 尚未就绪。请启动 Docker Desktop 并等待 Linux 容器引擎运行。'
    }
    if (($result.Output -join '').Trim() -ne 'linux') {
        throw '此演示版需要 Docker Desktop 的 Linux 容器引擎。请在 Docker Desktop 切换为 Linux containers。'
    }
}

function Assert-LocalDemoComposeConfig {
    param([string]$EnvPath = (Get-LocalDemoEnvPath))
    $base = Get-LocalDemoComposeArguments -EnvPath $EnvPath
    $null = Invoke-LocalDemoDocker -Arguments ($base + @('config', '--quiet')) -ConfigurationCommand
}

function Invoke-LocalDemoCompose {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [switch]$Quiet,
        [switch]$AllowFailure
    )
    $base = Get-LocalDemoComposeArguments
    return Invoke-LocalDemoDocker -Arguments ($base + $ComposeArguments) -Quiet:$Quiet -AllowFailure:$AllowFailure
}

function Invoke-LocalDemoBuild {
    param([Parameter(Mandatory = $true)][string[]]$ComposeArguments)
    if ($script:LocalDemoRoot -notmatch '[^\u0000-\u007F]') {
        return Invoke-LocalDemoCompose -ComposeArguments $ComposeArguments
    }
    if (-not [System.IO.Path]::IsPathRooted($script:LocalDemoRoot) -or $script:LocalDemoRoot[1] -ne ':') {
        throw '源码路径包含非 ASCII 字符，且无法创建 Windows 本地盘符映射。请将项目放到 ASCII 路径后重试构建。'
    }

    $substPath = Join-Path $env:SystemRoot 'System32/subst.exe'
    if (-not (Test-Path -LiteralPath $substPath -PathType Leaf)) {
        throw '源码路径包含非 ASCII 字符，但未找到 Windows subst.exe。请将项目放到 ASCII 路径后重试构建。'
    }
    $letter = @('Z', 'Y', 'X', 'W', 'V', 'U', 'T', 'S', 'R', 'Q', 'P', 'O', 'N', 'M', 'L', 'K', 'J', 'I', 'H', 'G', 'F', 'E', 'D') |
        Where-Object { -not (Get-PSDrive -Name $_ -PSProvider FileSystem -ErrorAction SilentlyContinue) } |
        Select-Object -First 1
    if (-not $letter) { throw '没有可用的临时盘符，无法为 Docker BuildKit 建立 ASCII 构建路径。' }

    $drive = $letter + ':'
    & $substPath $drive $script:LocalDemoRoot
    if ($LASTEXITCODE -ne 0) { throw '创建临时 Docker 构建路径失败。' }
    try {
        $driveRoot = $drive + '\'
        $arguments = @(
            'compose', '--env-file', (Join-Path $driveRoot '.env'),
            '-f', (Join-Path $driveRoot 'infra/compose.local-demo.yaml'),
            '-p', $script:LocalDemoProject
        ) + $ComposeArguments
        return Invoke-LocalDemoDocker -Arguments $arguments
    }
    finally {
        & $substPath $drive '/d' | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning '无法移除临时 Docker 构建盘符映射。' }
    }
}

function Get-LocalDemoImageDescriptors {
    return @(
        [pscustomobject]@{ Service = 'postgres'; Tag = 'aftersales-local-demo/postgres:local'; Source = 'pgvector/pgvector:pg16' },
        [pscustomobject]@{ Service = 'redis'; Tag = 'aftersales-local-demo/redis:local'; Source = 'redis:7.4-alpine' },
        [pscustomobject]@{ Service = 'backend'; Tag = 'aftersales-local-demo/backend:local'; Source = $null },
        [pscustomobject]@{ Service = 'web'; Tag = 'aftersales-local-demo/web:local'; Source = $null }
    )
}

function Get-LocalDemoImageId {
    param([Parameter(Mandatory = $true)][string]$Image)
    $result = Invoke-LocalDemoDocker -Arguments @('image', 'inspect', '--format', '{{.Id}}', $Image) -Quiet -AllowFailure
    if ($result.ExitCode -ne 0) { return $null }
    $id = ($result.Output -join '').Trim()
    if (-not $id) { return $null }
    return $id
}

function Get-LocalDemoManifestPath {
    if (Test-LocalDemoBundle) {
        $bundleManifest = Join-Path $script:LocalDemoRoot 'manifest.json'
        if (Test-Path -LiteralPath $bundleManifest -PathType Leaf) { return $bundleManifest }
        return $null
    }
    $buildManifest = Join-Path $script:LocalDemoRoot 'dist/local-demo/build-manifest.json'
    if (Test-Path -LiteralPath $buildManifest -PathType Leaf) { return $buildManifest }
    $manifest = Join-Path $script:LocalDemoRoot 'dist/local-demo/manifest.json'
    if (Test-Path -LiteralPath $manifest -PathType Leaf) { return $manifest }
    return $null
}

function Get-LocalDemoSourceFingerprint {
    if (Test-LocalDemoBundle) { return $null }
    $files = [System.Collections.Generic.List[string]]::new()
    foreach ($relative in @('pom.xml', 'backend/pom.xml', 'backend/Dockerfile', '.dockerignore',
            'frontend/package.json', 'frontend/package-lock.json', 'frontend/Dockerfile',
            'infra/compose.local-demo.yaml', 'infra/nginx/default.conf', 'infra/postgres/01-create-runtime-role.sh')) {
        $path = Join-Path $script:LocalDemoRoot $relative
        if (Test-Path -LiteralPath $path -PathType Leaf) { $files.Add($path) }
    }
    foreach ($directory in @('backend/src', 'frontend')) {
        $path = Join-Path $script:LocalDemoRoot $directory
        if (Test-Path -LiteralPath $path -PathType Container) {
            Get-ChildItem -LiteralPath $path -File -Recurse -Force |
                Where-Object { $_.FullName -notmatch '[\\/](node_modules|dist|target|\.git|test-results|playwright-report)[\\/]' } |
                ForEach-Object { $files.Add($_.FullName) }
        }
    }
    $uniqueFiles = [System.Collections.Generic.List[string]]::new()
    foreach ($path in $files | Select-Object -Unique) { $uniqueFiles.Add($path) }
    $uniqueFiles.Sort([System.StringComparer]::OrdinalIgnoreCase)
    $parts = [System.Collections.Generic.List[string]]::new()
    foreach ($path in $uniqueFiles) {
        $relative = $path.Substring($script:LocalDemoRoot.Length).TrimStart([char[]]@(92, 47)) -replace '\\', '/'
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $parts.Add("$relative`n$hash`n")
    }
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes(($parts -join ''))
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Assert-LocalDemoImagesMatchManifest {
    param([switch]$RequireManifest)
    $manifestPath = Get-LocalDemoManifestPath
    if (-not $manifestPath) {
        if ($RequireManifest) { throw '找不到本地镜像清单。请先运行 scripts/build-local-demo.ps1 或从镜像包运行 import-local-demo.ps1。' }
        return $false
    }
    try { $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json }
    catch { throw '本地镜像清单无法读取。请重新构建镜像或导入完整镜像包。' }
    foreach ($descriptor in Get-LocalDemoImageDescriptors) {
        $record = @($manifest.images | Where-Object { $_.service -eq $descriptor.Service -and $_.tag -eq $descriptor.Tag }) |
            Select-Object -First 1
        if (-not $record) { throw "镜像清单缺少服务 $($descriptor.Service)。请重新构建或导入镜像包。" }
        $currentId = Get-LocalDemoImageId -Image $descriptor.Tag
        if (-not $currentId) { throw "缺少 $($descriptor.Service) 镜像。请运行 scripts/import-local-demo.ps1 或 scripts/build-local-demo.ps1。" }
        if ($currentId -ne $record.imageId) { throw "本机 $($descriptor.Service) 镜像与清单中的 image ID 不一致。请重新导入/构建，或运行 .\scripts\start.ps1 -Build。" }
    }
    if (-not (Test-LocalDemoBundle) -and $manifest.sourceFingerprint) {
        $currentFingerprint = Get-LocalDemoSourceFingerprint
        if ($currentFingerprint -ne $manifest.sourceFingerprint) {
            throw '检测到源码或构建配置已变化，当前镜像可能过期。请运行 .\scripts\start.ps1 -Build 更新镜像。'
        }
    }
    return $true
}

function Test-LocalDemoPortAvailable {
    param([int]$Port)
    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        return $true
    }
    catch {
        $owned = Invoke-LocalDemoDocker -Arguments @('ps', '--filter', "label=com.docker.compose.project=$script:LocalDemoProject",
            '--filter', "publish=$Port", '-q') -Quiet -AllowFailure
        return ($owned.ExitCode -eq 0 -and ($owned.Output | Where-Object { $_.Trim() }).Count -gt 0)
    }
    finally { if ($listener) { $listener.Stop() } }
}
