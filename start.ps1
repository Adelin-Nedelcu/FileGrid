$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

function New-RandomBytes([int]$Count) {
    $bytes = New-Object byte[] $Count
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    return , $bytes
}

function New-RandomString([int]$Length) {
    $chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    -join ((New-RandomBytes $Length) | ForEach-Object { $chars[$_ % $chars.Length] })
}

$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path $envFile)) {
    Write-Host 'Generez .env cu secrete aleatoare...'
    $values = @{
        CATALOG_DB_PASSWORD     = New-RandomString 32
        STORAGE_DB_PASSWORD     = New-RandomString 32
        FTP_PASSWORD            = New-RandomString 32
        S3_ACCESS_KEY           = New-RandomString 20
        S3_SECRET_KEY           = New-RandomString 40
        FILEGRID_ENCRYPTION_KEY = [Convert]::ToBase64String((New-RandomBytes 32))
    }
    $lines = Get-Content (Join-Path $PSScriptRoot '.env.example') | ForEach-Object {
        if ($_ -match '^([A-Z0-9_]+)=$' -and $values.ContainsKey($Matches[1])) {
            "$($Matches[1])=$($values[$Matches[1]])"
        } else {
            $_
        }
    }
    [System.IO.File]::WriteAllLines($envFile, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
}

docker compose up --build -d @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$port = '8443'
$portLine = Get-Content $envFile | Where-Object { $_ -match '^HTTPS_PORT=(\d+)$' } | Select-Object -First 1
if ($portLine -match '^HTTPS_PORT=(\d+)$') { $port = $Matches[1] }
Write-Host ''
Write-Host "FileGrid: https://localhost:$port"
Write-Host '(certificat self-signed - accepta avertismentul browserului)'
