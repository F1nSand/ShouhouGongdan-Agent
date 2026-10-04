[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-demo/LocalDemo.Common.ps1')

try {
    $null = Ensure-LocalDemoEnv -AllowMissingModelKeys -NoGenerate
    Assert-LocalDemoDockerEngine
    Assert-LocalDemoComposeConfig
    $null = Invoke-LocalDemoCompose -ComposeArguments @('stop')
    Write-Host '本地演示服务已停止。数据库、政策文件和缓存数据卷均已保留。'
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
