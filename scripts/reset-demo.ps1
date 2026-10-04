[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-demo/LocalDemo.Common.ps1')

try {
    $null = Ensure-LocalDemoEnv -AllowMissingModelKeys -NoGenerate
    Assert-LocalDemoDockerEngine
    Assert-LocalDemoComposeConfig
    $project = Get-LocalDemoProjectName
    Write-Host "将停止并删除 $project 的容器和专用命名卷，包括演示订单、政策文件和 Redis 数据。其他 Compose 项目不会处理。"
    $confirmation = Read-Host "输入 $project 确认永久清除该演示栈数据"
    if ($confirmation -cne $project) {
        Write-Host '确认文字不匹配，未执行清理。'
        exit 2
    }

    $expectedServices = @('backend', 'postgres', 'redis', 'web')
    $actualServices = @(Invoke-LocalDemoCompose -ComposeArguments @('config', '--services') |
        ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    if (Compare-Object ($expectedServices | Sort-Object) ($actualServices | Sort-Object)) {
        throw 'Compose 服务与预期的四项演示服务不符，拒绝清理。'
    }
    $null = Invoke-LocalDemoCompose -ComposeArguments @('down', '--volumes', '--remove-orphans')
    $policyState = Join-Path (Get-LocalDemoRoot) 'logs/local-demo-policy-state.json'
    if (Test-Path -LiteralPath $policyState -PathType Leaf) { Remove-Item -LiteralPath $policyState -Force }
    Write-Host "$project 已清理。其它 Docker 项目和数据卷未修改。"
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
