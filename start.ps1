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

function New-Secret([string]$Name) {
    if ($Name -in 'FILEGRID_ENCRYPTION_KEY', 'FILEGRID_JWT_SECRET', 'METER_MASTER_KEY') {
        return [Convert]::ToBase64String((New-RandomBytes 32))
    }
    if ($Name -eq 'S3_ACCESS_KEY') { return New-RandomString 20 }
    if ($Name -eq 'S3_SECRET_KEY') { return New-RandomString 40 }
    if ($Name -like 'PASSWORD_*') { return New-RandomString 14 }
    return New-RandomString 32
}

$envFile = Join-Path $PSScriptRoot '.env'
$current = @()
if (Test-Path $envFile) { $current = @(Get-Content $envFile) }

$known = @{}
foreach ($line in $current) {
    if ($line -match '^([A-Z0-9_]+)=(.+)$') { $known[$Matches[1]] = $true }
}

$added = @()
foreach ($line in Get-Content (Join-Path $PSScriptRoot '.env.example')) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$' -and -not $known.ContainsKey($Matches[1])) {
        $name = $Matches[1]
        $value = $Matches[2]
        if ($value -eq '') { $value = New-Secret $name }
        $added += "$name=$value"
    }
}

if ($added.Count -gt 0) {
    Write-Host "Completez .env cu $($added.Count) valori generate..."
    $kept = @($current | Where-Object { $_ -notmatch '^[A-Z0-9_]+=$' })
    [System.IO.File]::WriteAllLines($envFile, [string[]]($kept + $added), (New-Object System.Text.UTF8Encoding($false)))
}

docker compose up --build -d @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$port = '8443'
$portLine = Get-Content $envFile | Where-Object { $_ -match '^HTTPS_PORT=(\d+)$' } | Select-Object -First 1
if ($portLine -match '^HTTPS_PORT=(\d+)$') { $port = $Matches[1] }
Write-Host ''
Write-Host "FileGrid: https://localhost:$port"
Write-Host '(certificat self-signed - accepta avertismentul browserului)'
Write-Host 'Conturi: admin, dispecer, teren, racordari - parolele sunt in .env (PASSWORD_...)'
Write-Host 'Alerte (e-mail): http://localhost:8025   Prometheus: http://localhost:9090'
