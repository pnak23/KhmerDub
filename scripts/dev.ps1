<#
.SYNOPSIS
    Start, stop or inspect the KhmerDub AI local stack.

.DESCRIPTION
    Infrastructure (PostgreSQL, Redis) and the Celery worker run in Docker Desktop.
    The API (Spring Boot), AI service (FastAPI) and web app (Nuxt) run natively on Windows.
    Logs go to <storage root>\logs. Process IDs are kept in .run\ (git-ignored).
    Nothing here changes PATH, JAVA_HOME or any other system setting; variables are set for this
    process and its children only.

.EXAMPLE
    .\scripts\dev.ps1 start                  # everything, Linux worker in Docker (default)
    .\scripts\dev.ps1 start -Worker windows  # fallback: native Windows worker (solo pool)
    .\scripts\dev.ps1 status
    .\scripts\dev.ps1 stop                   # app processes + worker; PostgreSQL/Redis keep running
    .\scripts\dev.ps1 stop -All              # also stop PostgreSQL and Redis containers
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)][ValidateSet('start', 'stop', 'status')][string]$Command,
    [ValidateSet('linux', 'windows', 'none')][string]$Worker = 'linux',
    # Start/stop only one part of the stack (e.g. restart just the API). Default: everything.
    [ValidateSet('all', 'api', 'ai', 'web', 'worker')][string]$Service = 'all',
    [switch]$All
)

# 'Continue': in Windows PowerShell 5.1, captured stderr from native tools (docker compose writes progress
# there) would otherwise become a terminating error. Failures are checked explicitly through exit codes.
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $Root '.env'
if (-not (Test-Path $EnvFile)) { throw '.env not found. Copy .env.example to .env and fill it in.' }

# --- configuration from .env (exported to this process and its children only) -------------------
$Cfg = @{}
foreach ($line in Get-Content $EnvFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $Cfg[$Matches[1]] = $Matches[2].Trim()
        Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2].Trim()
    }
}
$JavaHome = if ($env:KHMERDUB_JAVA_HOME) { $env:KHMERDUB_JAVA_HOME } else { Join-Path $env:USERPROFILE '.jdks\corretto-21.0.6' }
$StorageRoot = $Cfg['KHMERDUB_STORAGE_ROOT'] -replace '/', '\'
$LogDir = Join-Path $StorageRoot 'logs'
$RunDir = Join-Path $Root '.run'
$Py = Join-Path $Root 'apps\ai\.venv\Scripts\python.exe'
$ComposeArgs = @('compose', '-f', (Join-Path $Root 'infra\docker-compose.yml'), '--env-file', $EnvFile, '--profile', 'linux-worker')
New-Item -ItemType Directory -Force -Path $LogDir, $RunDir | Out-Null

$Services = [ordered]@{
    api        = @{ Dir = 'apps\api'; Exe = (Join-Path $Root 'apps\api\mvnw.cmd'); Args = @('-q', 'spring-boot:run'); Port = [int]$Cfg['API_PORT'] }
    ai         = @{ Dir = 'apps\ai'; Exe = $Py; Args = @('-m', 'uvicorn', 'khmerdub_ai.main:app', '--host', '127.0.0.1', '--port', $Cfg['AI_PORT']); Port = [int]$Cfg['AI_PORT'] }
    'worker-win' = @{ Dir = 'apps\ai'; Exe = $Py; Args = @('-m', 'celery', '-A', 'khmerdub_ai.celery_app', 'worker', '-Q', 'cpu', '-P', 'solo', '-n', 'cpu-win@%h', '--loglevel=INFO'); Port = $null }
    web        = @{ Dir = 'apps\web'; Exe = (Get-Command node).Source; Args = @('node_modules\nuxt\bin\nuxt.mjs', 'dev'); Port = [int]$Cfg['WEB_PORT'] }
}

function Invoke-Docker([string[]]$DockerArgs) {
    & docker @ComposeArgs @DockerArgs 2>&1 | ForEach-Object { "  $_" }
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($DockerArgs -join ' ') failed (exit $LASTEXITCODE). Is Docker Desktop running?" }
}

function Get-ServicePid([string]$name) {
    $file = Join-Path $RunDir "$name.pid"
    if (-not (Test-Path $file)) { return $null }
    $id = [int](Get-Content $file)
    if (Get-Process -Id $id -ErrorAction SilentlyContinue) { return $id }
    Remove-Item $file -Force
    return $null
}

function Test-Port([int]$port) {
    [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}

function Start-AppService([string]$name) {
    $svc = $Services[$name]
    if (Get-ServicePid $name) { Write-Host "  $name already running"; return }
    if ($svc.Port -and (Test-Port $svc.Port)) { throw "Port $($svc.Port) for '$name' is already in use by another process." }
    $p = Start-Process -FilePath $svc.Exe -ArgumentList $svc.Args -WorkingDirectory (Join-Path $Root $svc.Dir) `
        -RedirectStandardOutput (Join-Path $LogDir "$name.log") -RedirectStandardError (Join-Path $LogDir "$name.err.log") `
        -WindowStyle Hidden -PassThru -ErrorAction Stop
    Set-Content -Path (Join-Path $RunDir "$name.pid") -Value $p.Id
    Write-Host "  $name started (pid $($p.Id))"
}

function Stop-AppService([string]$name) {
    $id = Get-ServicePid $name
    if (-not $id) { return }
    if ($name -eq 'worker-win') {
        # Graceful: the solo worker finishes its current task, then exits.
        & $Py -m celery -A khmerdub_ai.celery_app control shutdown --timeout 2 *> $null
        foreach ($i in 1..30) { if (-not (Get-Process -Id $id -ErrorAction SilentlyContinue)) { break }; Start-Sleep -Seconds 1 }
    }
    if (Get-Process -Id $id -ErrorAction SilentlyContinue) {
        # /T: on Windows the venv python.exe and mvnw.cmd are launchers; the real process is a child.
        taskkill /T /F /PID $id *> $null
    }
    Remove-Item (Join-Path $RunDir "$name.pid") -Force -ErrorAction SilentlyContinue
    Write-Host "  $name stopped"
}

function Wait-Port([string]$name, [int]$port, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $port) { Write-Host "  $name listening on 127.0.0.1:$port"; return }
        if (-not (Get-ServicePid $name)) { Write-Warning "$name exited. See $LogDir\$name.err.log"; return }
        Start-Sleep -Seconds 1
    }
    Write-Warning "$name not listening on port $port after $timeoutSec s. See $LogDir\$name.log"
}

switch ($Command) {
    'start' {
        Write-Host 'Infrastructure (Docker):'
        Invoke-Docker @('up', '-d', '--wait', 'postgres', 'redis')

        Write-Host 'Services:'
        $env:JAVA_HOME = $JavaHome
        $env:PATH = "$JavaHome\bin;$env:PATH"
        foreach ($name in 'api', 'ai', 'web') {
            if ($Service -in 'all', $name) { Start-AppService $name }
        }
        if ($Service -in 'all', 'worker') {
            switch ($Worker) {
                'linux' { Invoke-Docker @('up', '-d', 'worker-cpu') }
                'windows' { Start-AppService 'worker-win' }
                'none' { Write-Host '  no worker started' }
            }
        }
        if ($Service -in 'all', 'ai') { Wait-Port 'ai' $Services.ai.Port 30 }
        if ($Service -in 'all', 'web') { Wait-Port 'web' $Services.web.Port 90 }
        if ($Service -in 'all', 'api') { Wait-Port 'api' $Services.api.Port 180 }
        Write-Host "Open http://localhost:$($Cfg['WEB_PORT'])  (logs: $LogDir)"
    }
    'stop' {
        foreach ($name in 'web', 'api', 'ai') {
            if ($Service -in 'all', $name) { Stop-AppService $name }
        }
        if ($Service -in 'all', 'worker') {
            Stop-AppService 'worker-win'
            Invoke-Docker @('stop', 'worker-cpu')
        }
        if ($All) { Invoke-Docker @('stop') }
    }
    'status' {
        foreach ($name in $Services.Keys) {
            $id = Get-ServicePid $name
            $port = $Services[$name].Port
            $listen = if ($port) { if (Test-Port $port) { "listening :$port" } else { "NOT listening :$port" } } else { '' }
            Write-Host ("  {0,-11} {1,-16} {2}" -f $name, $(if ($id) { "running ($id)" } else { 'stopped' }), $listen)
        }
        Invoke-Docker @('ps', '--format', 'table {{.Service}}\t{{.Status}}\t{{.Ports}}')
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$($Cfg['API_PORT'])/api/health" -TimeoutSec 10
            Write-Host "Overall: $($h.status)"
            foreach ($c in $h.components.PSObject.Properties) { Write-Host ("  {0,-10} {1}" -f $c.Name, $c.Value.status) }
        } catch {
            Write-Host 'Overall: API not reachable'
        }
    }
}
