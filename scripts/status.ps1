[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-demo/LocalDemo.Common.ps1')

try {
    $null = Ensure-LocalDemoEnv -AllowMissingModelKeys -NoGenerate
    Assert-LocalDemoDockerEngine
    Assert-LocalDemoComposeConfig
    $port = Get-LocalDemoWebPort
    Write-Host 'aftersales-local-demo 服务状态：'
    $null = Invoke-LocalDemoCompose -ComposeArguments @('ps', '--all')
    Write-Host "工作台地址：http://127.0.0.1:$port"
    Write-Host '后端 HTTP readiness 可从容器网络检查：scripts/start.ps1 会执行该检查。'
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
