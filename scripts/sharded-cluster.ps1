<#
  Runs a real MongoDB sharded cluster on this computer for the Hospital Management System.

    config server   replica set "cfg"     port 27101   (stores which shard owns which range of data)
    shard1          replica set "shard1"  port 27201
    shard2          replica set "shard2"  port 27202
    mongos router                         port 27100   (the application connects here)

  Usage (from the project root, in PowerShell):
    .\scripts\sharded-cluster.ps1 start                  start everything; the first time, also set up sharding
    .\scripts\sharded-cluster.ps1 status                 which processes run, and how the data is spread
    .\scripts\sharded-cluster.ps1 add-shard shard3 27203 start a third shard and let the balancer move data to it
    .\scripts\sharded-cluster.ps1 kill shard2            stop one shard abruptly (failure test)
    .\scripts\sharded-cluster.ps1 revive shard2 27202    start a stopped shard again
    .\scripts\sharded-cluster.ps1 stop                   stop everything, keep the data
    .\scripts\sharded-cluster.ps1 reset                  stop everything and delete the cluster's data

  Then start the application against the router:
    java -jar hms-app\target\hms-app.jar --spring.mongodb.uri=mongodb://127.0.0.1:27100/hms_sharded

  Data and logs go to %LOCALAPPDATA%\hms-sharded (outside OneDrive, which would otherwise try to sync
  database files). Each server uses at most 256 MB of cache so the whole cluster fits on a laptop.
  Requires the MongoDB server binaries (mongod and mongos, version 7.1 or newer) and a built hms-bench.jar.
#>
param(
    [Parameter(Position = 0)] [string] $Command = "status",
    [Parameter(Position = 1)] [string] $Name,
    [Parameter(Position = 2)] [int] $Port
)
$ErrorActionPreference = "Stop"

$Root = Join-Path $env:LOCALAPPDATA "hms-sharded"
$Project = Split-Path -Parent $PSScriptRoot
$BenchJar = Join-Path $Project "hms-bench\target\hms-bench.jar"
$RouterPort = 27100

function Find-MongoBinary([string] $exe) {
    $cmd = Get-Command $exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $found = Get-ChildItem "C:\Program Files\MongoDB\Server\*\bin\$exe.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $found) { throw "$exe was not found. Install the MongoDB server or add its bin folder to PATH." }
    return $found.FullName
}

function Test-Port([int] $p) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect("127.0.0.1", $p); return $true } catch { return $false } finally { $client.Dispose() }
}

function Wait-Port([int] $p, [string] $what) {
    for ($i = 0; $i -lt 120; $i++) {
        if (Test-Port $p) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "$what did not start listening on port $p; see its log in $Root"
}

function Start-Member([string] $name, [int] $port, [string] $role) {
    if (Test-Port $port) { Write-Host "  $name already running on port $port"; return }
    $dir = Join-Path $Root $name
    New-Item -ItemType Directory -Force $dir | Out-Null
    $mongod = Find-MongoBinary "mongod"
    $arguments = @("--$role", "--replSet", $name, "--port", "$port", "--bind_ip", "127.0.0.1",
                   "--dbpath", $dir, "--logpath", (Join-Path $Root "$name.log"), "--logappend",
                   "--wiredTigerCacheSizeGB", "0.25")
    $process = Start-Process -FilePath $mongod -ArgumentList $arguments -WindowStyle Hidden -PassThru
    Set-Content -Path (Join-Path $Root "$name.pid") -Value $process.Id -Encoding ascii
    Wait-Port $port $name
    Write-Host "  started $name (pid $($process.Id), port $port)"
}

function Start-Router {
    if (Test-Port $RouterPort) { Write-Host "  router already running on port $RouterPort"; return }
    $mongos = Find-MongoBinary "mongos"
    $arguments = @("--configdb", "cfg/127.0.0.1:27101", "--port", "$RouterPort", "--bind_ip", "127.0.0.1",
                   "--logpath", (Join-Path $Root "mongos.log"), "--logappend")
    $process = Start-Process -FilePath $mongos -ArgumentList $arguments -WindowStyle Hidden -PassThru
    Set-Content -Path (Join-Path $Root "mongos.pid") -Value $process.Id -Encoding ascii
    Wait-Port $RouterPort "mongos"
    Write-Host "  started mongos router (pid $($process.Id), port $RouterPort)"
}

function Stop-Member([string] $name, [switch] $Abrupt) {
    $pidFile = Join-Path $Root "$name.pid"
    if (-not (Test-Path $pidFile)) { return }
    $id = [int](Get-Content $pidFile)
    $process = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -match "^mongo") {
        Stop-Process -Id $id -Force
        Write-Host "  stopped $name (pid $id)$(if ($Abrupt) { ' abruptly' })"
    }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
}

function Invoke-Tool([string[]] $toolArgs) {
    if (-not (Test-Path $BenchJar)) { throw "hms-bench.jar not found; build first with: mvn package -DskipTests" }
    Push-Location $Project
    try {
        & java -jar $BenchJar shard @toolArgs
        if ($LASTEXITCODE -ne 0) { throw "hms-bench shard $toolArgs failed" }
    } finally { Pop-Location }
}

New-Item -ItemType Directory -Force $Root | Out-Null
$marker = Join-Path $Root "initialized"

switch ($Command) {
    "start" {
        Write-Host "Starting the sharded cluster (data in $Root)"
        Start-Member "cfg" 27101 "configsvr"
        Start-Member "shard1" 27201 "shardsvr"
        Start-Member "shard2" 27202 "shardsvr"
        # Members that were added later (for example shard3) are restarted too.
        Get-ChildItem $Root -Filter "*.port" -ErrorAction SilentlyContinue | ForEach-Object {
            Start-Member $_.BaseName ([int](Get-Content $_.FullName)) "shardsvr"
        }
        if (-not (Test-Path $marker)) {
            Invoke-Tool @("init-rs", "cfg:27101:config", "shard1:27201", "shard2:27202")
        }
        Start-Router
        if (-not (Test-Path $marker)) {
            Invoke-Tool @("setup", "shard1:27201", "shard2:27202")
            Set-Content -Path $marker -Value (Get-Date -Format o) -Encoding ascii
        }
        Write-Host "Ready. Application database URI: mongodb://127.0.0.1:${RouterPort}/hms_sharded"
    }
    "add-shard" {
        if (-not $Name -or -not $Port) { throw "usage: add-shard NAME PORT, for example add-shard shard3 27203" }
        Start-Member $Name $Port "shardsvr"
        Set-Content -Path (Join-Path $Root "$Name.port") -Value $Port -Encoding ascii
        Invoke-Tool @("add", "${Name}:$Port")
    }
    "kill" {
        if (-not $Name) { throw "usage: kill NAME, for example kill shard2" }
        Stop-Member $Name -Abrupt
    }
    "revive" {
        if (-not $Name -or -not $Port) { throw "usage: revive NAME PORT, for example revive shard2 27202" }
        Start-Member $Name $Port "shardsvr"
    }
    "status" {
        foreach ($p in @(@("mongos", $RouterPort), @("cfg", 27101), @("shard1", 27201), @("shard2", 27202))) {
            Write-Host ("  {0,-7} port {1}: {2}" -f $p[0], $p[1], $(if (Test-Port $p[1]) { "running" } else { "stopped" }))
        }
        Get-ChildItem $Root -Filter "*.port" -ErrorAction SilentlyContinue | ForEach-Object {
            $p = [int](Get-Content $_.FullName)
            Write-Host ("  {0,-7} port {1}: {2}" -f $_.BaseName, $p, $(if (Test-Port $p) { "running" } else { "stopped" }))
        }
        if (Test-Port $RouterPort) { Invoke-Tool @("status") }
    }
    "stop" {
        Stop-Member "mongos"
        Get-ChildItem $Root -Filter "*.pid" -ErrorAction SilentlyContinue | ForEach-Object { Stop-Member $_.BaseName }
        Write-Host "Cluster stopped; data kept in $Root"
    }
    "reset" {
        Stop-Member "mongos"
        Get-ChildItem $Root -Filter "*.pid" -ErrorAction SilentlyContinue | ForEach-Object { Stop-Member $_.BaseName }
        Start-Sleep -Seconds 1
        Remove-Item -Recurse -Force $Root
        Write-Host "Cluster stopped and its data deleted"
    }
    default { throw "unknown command '$Command' (start, status, add-shard, kill, revive, stop, reset)" }
}
