param(
    [Parameter(Mandatory = $true)][int]$ServerPid,
    [Parameter(Mandatory = $true)][string]$BackendContainer,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [string]$FrontPort = '8066',
    [string]$DatasourceName = 'debt143MaskingProbe'
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
if (-not (Get-Process -Id $ServerPid -ErrorAction SilentlyContinue)) {
    throw "server process not found: $ServerPid"
}

function Invoke-FrontSql([string]$sql) {
    $escaped = $sql.Replace("'", "'\''")
    $command = "mysql --protocol=tcp -hhost.docker.internal -P$FrontPort -uroot --get-server-public-key --batch -e '$escaped'"
    $output = docker exec -e MYSQL_PWD=123456 $BackendContainer sh -c $command 2>&1
    if ($LASTEXITCODE -ne 0 -or (($output -join "`n") -match 'ERROR\s+\d+')) {
        throw "front SQL failed: $($output -join "`n")"
    }
    return ($output -join "`n").Trim()
}

$summary = [System.Collections.Generic.List[string]]::new()
$created = $false
$verdict = 'FAIL'
$stage = 'start'
try {
    $config = [ordered]@{
        name = $DatasourceName
        user = 'root'
        password = '123456'
        type = 'JDBC'
        dbType = 'mysql'
        url = 'jdbc:mysql://root:123456@127.0.0.1:3306/mysql?password=123456&user=root&token=live-token&api_key=live-key&useSSL=false'
        maxCon = 2
        minCon = 0
        maxRetryCount = 0
        idleTimeout = 120000
    } | ConvertTo-Json -Compress
    Invoke-FrontSql "/*+ mycat:createDataSource$config */;" | Out-Null
    $created = $true
    $summary.Add("create`thint_ok")
    $stage = 'result_shape'

    $output = Invoke-FrontSql '/*+ mycat:showDataSources{} */;'
    $lines = $output -split "`r?`n"
    $columnNames = $lines[0] -split "`t"
    $row = $lines | Where-Object { $_.StartsWith($DatasourceName + "`t") } | Select-Object -First 1
    if (-not $row) { throw 'probe datasource row not found' }
    $values = $row -split "`t"
    if ($columnNames.Count -ne 19 -or $values.Count -ne 19) {
        throw "unexpected result shape: columns=$($columnNames.Count) values=$($values.Count)"
    }
    $rowMap = @{}
    for ($i = 0; $i -lt $columnNames.Count; $i++) { $rowMap[$columnNames[$i]] = $values[$i] }
    $summary.Add("column_count`t$($columnNames.Count)")
    $expectedColumns = @('NAME', 'USERNAME', 'PASSWORD', 'MAX_CON', 'MIN_CON', 'EXIST_CON', 'USE_CON',
        'MAX_RETRY_COUNT', 'MAX_CONNECT_TIMEOUT', 'DB_TYPE', 'URL', 'WEIGHT', 'INIT_SQL',
        'INIT_SQL_GET_CONNECTION', 'INSTANCE_TYPE', 'IDLE_TIMEOUT', 'DRIVER', 'TYPE', 'IS_MYSQL')
    if (($columnNames -join ',') -ne ($expectedColumns -join ',')) { throw 'unexpected result column names or order' }
    $summary.Add('column_order' + "`t" + 'locked')
    $stage = 'password_column'

    if ($rowMap['PASSWORD'] -ne '******') { throw 'PASSWORD column is not masked' }
    $summary.Add("password_column`tmasked")
    $stage = 'url_target'

    $url = $rowMap['URL']
    if ($url -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/mysql\?') { throw 'URL lost its non-sensitive target' }
    if ($url.Contains('@')) { throw 'URL authority user-info remains' }
    $summary.Add("url_target`tpreserved")
    $summary.Add("url_user_info`tabsent")
    $stage = 'url_forbidden'

    foreach ($forbidden in @('root:123456@', 'password=123456', 'user=root', 'token=live-token', 'api_key=live-key')) {
        if ($url -imatch [regex]::Escape($forbidden)) {
            throw "URL still contains forbidden credential text: $forbidden"
        }
        $summary.Add("url_forbidden_$($forbidden.Length)`tabsent")
    }

    $stage = 'url_sensitive_keys'
    foreach ($key in @('password', 'user', 'token', 'api_key')) {
        if ($url -notmatch ("(?i)" + [regex]::Escape($key) + "=\*\*\*\*\*\*")) {
            throw "URL sensitive property is not masked: $key"
        }
        $summary.Add("url_${key}`tmasked")
    }
    $stage = 'url_useSSL'
    if ($url -notmatch '(?i)useSSL=false') { throw 'URL non-sensitive property was lost' }
    $summary.Add('url_useSSL' + "`t" + 'preserved')
    $stage = 'driver'

    $driver = $rowMap['DRIVER']
    if ($driver -ne 'com.alibaba.druid.pool.DruidDataSource') { throw 'DRIVER is not the explicit pool class name' }
    if ($driver.Contains('@')) { throw 'DRIVER still contains object identity text' }
    $summary.Add("driver`t$driver")
    $summary.Add('driver_toString' + "`t" + 'absent')
    $stage = 'front_alive'

    $aliveOutput = Invoke-FrontSql 'SELECT 1'
    $alive = ($aliveOutput -split "`r?`n" | Select-Object -Last 1)
    if ($alive -ne '1') { throw "front alive probe returned: $alive" }
    $summary.Add("front_alive`t$alive")
    $verdict = 'PASS'
} catch {
    $summary.Add("failed_stage`t$stage")
    $summary.Add("error_type`t$($_.Exception.GetType().FullName)")
} finally {
    if ($created) {
        try {
            $drop = (@{ name = $DatasourceName } | ConvertTo-Json -Compress)
            Invoke-FrontSql "/*+ mycat:dropDataSource$drop */;" | Out-Null
            $summary.Add('cleanup' + "`t" + 'drop_hint_ok')
        } catch {
            $summary.Add('cleanup' + "`t" + 'FAIL')
            $verdict = 'FAIL'
        }
    }
    $summary.Add("verdict`t$verdict")
    $summary | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'summary.tsv')
}
if ($verdict -ne 'PASS') { exit 1 }
