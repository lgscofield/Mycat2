param(
    [Parameter(Mandatory = $true)][int]$ServerPid,
    [Parameter(Mandatory = $true)][string]$BackendContainer,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [string]$JdkHome = 'D:\Program Files\BellSoft\LibericaJDK-21-Full',
    [string]$FrontPort = '8066',
    [string]$ServerLog = '',
    [string]$DatasourceName = 'debt136NativeReload',
    [int]$Reloads = 5
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$jcmd = Join-Path $JdkHome 'bin\jcmd.exe'
if (-not (Test-Path $jcmd)) { throw "jcmd not found: $jcmd" }
if (-not (Get-Process -Id $ServerPid -ErrorAction SilentlyContinue)) { throw "server process not found: $ServerPid" }

function Invoke-FrontSql([string]$sql) {
    $escaped = $sql.Replace("'", "'\''")
    $command = "mysql --protocol=tcp -hhost.docker.internal -P$FrontPort -uroot --get-server-public-key --batch --skip-column-names -e '$escaped'"
    $output = docker exec -e MYSQL_PWD=123456 $BackendContainer sh -c $command 2>&1
    if ($LASTEXITCODE -ne 0 -or (($output -join "`n") -match 'ERROR\s+\d+')) {
        throw "front SQL failed: $($output -join "`n")"
    }
    return ($output -join "`n").Trim()
}

function Get-NativeHistogram([string]$phase) {
    $path = Join-Path $EvidenceDir "heap-$phase.txt"
    & $jcmd $ServerPid GC.class_histogram | Set-Content -Encoding utf8 $path
    if ($LASTEXITCODE -ne 0) { throw "jcmd failed during $phase" }
    $lines = Get-Content $path | Where-Object {
        $_ -match 'io\.mycat\.mysqlclient\.(MycatNativeDatasourcePool|VertxPoolConnectionImpl|MycatDatasourcePool)\s'
    }
    if (-not $lines) { $lines = @('NO_NATIVE_POOL_CLASSES') }
    $lines | Set-Content -Encoding utf8 (Join-Path $EvidenceDir "heap-$phase-native.txt")
    return $lines
}

function Get-ClassCount([string[]]$lines, [string]$className) {
    $line = $lines | Where-Object { $_ -match ('\s' + [regex]::Escape($className) + '\s') } | Select-Object -First 1
    if (-not $line) { return 0 }
    return [int](($line.Trim() -split '\s+')[0])
}

$summary = [System.Collections.Generic.List[string]]::new()
$summary.Add("phase`tMycatNativeDatasourcePool`tVertxPoolConnectionImpl`tMycatDatasourcePool")
$baseline = Get-NativeHistogram 'baseline'
$summary.Add(('baseline`t{0}`t{1}`t{2}' -f `
    (Get-ClassCount $baseline 'io.mycat.mysqlclient.MycatNativeDatasourcePool'),
    (Get-ClassCount $baseline 'io.mycat.mysqlclient.VertxPoolConnectionImpl'),
    (Get-ClassCount $baseline 'io.mycat.mysqlclient.MycatDatasourcePool')))

try {
    for ($i = 1; $i -le $Reloads; $i++) {
        $config = [ordered]@{
            name = $DatasourceName
            user = 'root'
            password = '123456'
            type = 'NATIVE'
            dbType = 'mysql'
            url = "jdbc:mysql://127.0.0.1:$(13300 + $i)/mysql"
            maxCon = 1
            minCon = 0
            maxRetryCount = 0
            idleTimeout = 120000
        } | ConvertTo-Json -Compress
        Invoke-FrontSql "/*+ mycat:createDataSource$config */;" | Out-Null
        $summary.Add("reload_$i`tcreate_hint_ok`tport=$(13300 + $i)")
    }

    if ($ServerLog) {
        $fallbackCount = @(Select-String -Path $ServerLog -SimpleMatch "native datasource pool failed for $DatasourceName").Count
        $summary.Add("fallback_log_count`t$fallbackCount")
        if ($fallbackCount -lt $Reloads) { throw "expected at least $Reloads native fallback logs, got $fallbackCount" }
    }

    Start-Sleep -Seconds 35
    & $jcmd $ServerPid GC.run | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'jcmd GC.run failed' }
    $after = Get-NativeHistogram 'after-reloads-and-deferred-close'
    $summary.Add(('after`t{0}`t{1}`t{2}' -f `
        (Get-ClassCount $after 'io.mycat.mysqlclient.MycatNativeDatasourcePool'),
        (Get-ClassCount $after 'io.mycat.mysqlclient.VertxPoolConnectionImpl'),
        (Get-ClassCount $after 'io.mycat.mysqlclient.MycatDatasourcePool')))

    $nativeAfter = Get-ClassCount $after 'io.mycat.mysqlclient.MycatNativeDatasourcePool'
    $rawAfter = Get-ClassCount $after 'io.mycat.mysqlclient.VertxPoolConnectionImpl'
    if ($nativeAfter -ne 0 -or $rawAfter -ne 0) {
        throw "native pool objects leaked: native=$nativeAfter raw=$rawAfter"
    }
    $summary.Add("verdict`tPASS`t${Reloads}_failed_native_probes_fell_back_and_collected")
    Invoke-FrontSql 'SELECT 1' | ForEach-Object { $summary.Add("front_alive`t$_") }
} finally {
    try {
        $drop = (@{ name = $DatasourceName } | ConvertTo-Json -Compress)
        Invoke-FrontSql "/*+ mycat:dropDataSource$drop */;" | Out-Null
        $summary.Add('cleanup`tdrop_hint_ok')
    } catch {
        $summary.Add('cleanup`tFAIL`t' + $_.Exception.Message)
    }
    $summary | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'summary.tsv')
}
