[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$templatePath = Join-Path $projectRoot '.env.example'
$envPath = Join-Path $projectRoot '.env'

if (Test-Path -LiteralPath $envPath) {
    throw 'Refusing to overwrite .env. Remove it yourself if you want to generate a new set of local credentials.'
}

function New-Base64Secret([int]$byteCount) {
    $bytes = [byte[]]::new($byteCount)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

function New-UrlSafeSecret([int]$byteCount) {
    return (New-Base64Secret $byteCount).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$content = Get-Content -LiteralPath $templatePath -Raw
$postgresPassword = New-UrlSafeSecret 24
$secrets = [ordered]@{
    POSTGRES_PASSWORD = $postgresPassword
    DB_APP_PASSWORD = New-UrlSafeSecret 24
    DB_MIGRATION_PASSWORD = $postgresPassword
    JWT_SECRET = New-Base64Secret 32
    DEMO_CSR_PASSWORD = New-UrlSafeSecret 18
    DEMO_SUPERVISOR_PASSWORD = New-UrlSafeSecret 18
    DEMO_WAREHOUSE_PASSWORD = New-UrlSafeSecret 18
    DEMO_ADMIN_PASSWORD = New-UrlSafeSecret 18
}

foreach ($entry in $secrets.GetEnumerator()) {
    $pattern = '(?m)^' + [Regex]::Escape($entry.Key) + '=.*$'
    $content = [Regex]::Replace($content, $pattern, $entry.Key + '=' + $entry.Value)
}

[System.IO.File]::WriteAllText($envPath, $content, [System.Text.UTF8Encoding]::new($false))
Write-Output "Created $envPath with randomly generated local credentials. Values were not printed."
