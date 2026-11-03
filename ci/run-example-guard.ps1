<#
.I-DEBT-79 刀①：本地收口护栏的固定入口（durable，替代散落的 scratch/*.ps1 手敲命令）
.SYNOPSIS
  ci/run-example-guard.ps1 -Persona MYSQL_8_0 [-Modules server,example] [-Test <类或方法>] [-GateOnly] [-SkipRestart]
.DESCRIPTION
  为什么需要它（实测根因，非推断）：example 全套是**跨进程 JDBC 客户端**连外部 8066
  （example/pom.xml:155、MycatTest.java:30-33），而 8066 是长命有状态进程——上一段活体取证用
  hint mycat:createDataSource 把 prototypeDs 热重绑到别的后端后若不重启，全量护栏会连到
  "上一段留下的后端画像"，用例期望值却按 mysql:8.0 写死 ⇒ 制造假红
  （2026-11-03 那轮 example 550/9F/3E/37skip 中的 6 类红即此因，11-04 干净重启后整体消失）。
  本脚本把"干净前置"固化成五步，任何一步不符就 abort 不跑测试（宁 NO_DATA 不假红/不伪绿）：
    0) persona 白名单校验（-Dmycat.persona 非法值会被 PersonaOverride 静默回退 = 假绿温床）
    1) 杀 8066 → 用指定 jar + persona 重启（内存脏态随进程一起消失）
    2) 就绪 = TCP 通 且 读到握手包首字节，再 settle（替代纯 TCP 判据）
    3) posture gate：前端 select version() 必须落在 persona 宣称的版本族；
       后端取**磁盘态** prototypeDs.datasource.json 的 url host:port 直连取 version()，
       引擎族（MySQL vs MariaDB）必须与 persona 一致
    4) 跑 mvn -o -B -pl <Modules> -am test（-am 硬要求：不带会从本地仓库取上游 jar，引入第二变量）；
       给 -Test 时追加 -Dtest=<值> -Dsurefire.failIfNoSpecifiedTests=false，用于 I-DEBT-80 那类隔离取证
limitations
  - gate 拦不住"只改内存的热重绑"（磁盘态仍是干净的）；那种脏态由第 1 步 kill+restart 结构性消除。
    故 -SkipRestart 仅供取证观察，此时第 3 步的后端腿反映的是磁盘态而非进程内存态，务必自行判断可信度。
  - 后端版本探针需要容器内 mysql 客户端连宿主端口；连 Mycat(8066) 必须带 --get-server-public-key，
    否则非 TLS 的 caching_sha2 full-auth 报 ERROR 2061 (HY000) ... requires secure connection（仪器坑，非产品坑）。
#>
param(
    [Parameter(Mandatory = $true)][string]$Persona,
    [string]$Jar = "$PSScriptRoot\..\server\target\server-1.22-release-jar-with-dependencies.jar",
    [string]$Modules = 'server,example',
    [string]$MycatHome = '',
    [string]$BackendContainer = 'm1-mysql-a',
    [string]$DsName = 'prototypeDs',
    [string]$JdkHome = '',
    [string]$Test = '',
    [switch]$AllowAnyJdk,
    [switch]$GateOnly,
    [switch]$KeepRunning,
    [switch]$SkipRestart
)
$ErrorActionPreference = 'Continue'

# --- 画像表白：值取自 config/src/main/java/io/mycat/config/ServerPersona.java，默认版本串取自 defaultVersionString ---
$Personas = @{
    'MYSQL_5_7'      = @{ Ver = '^5\.7\.';  Engine = 'MySQL' }
    'MYSQL_8_0'      = @{ Ver = '^8\.0\.';  Engine = 'MySQL' }
    'MYSQL_8_4'      = @{ Ver = '^8\.4\.';  Engine = 'MySQL' }
    'MYSQL_9_X'      = @{ Ver = '^9\.';     Engine = 'MySQL' }
    'MARIADB_10_6'   = @{ Ver = '^10\.6\.'; Engine = 'MariaDB' }
    'MARIADB_10_11'  = @{ Ver = '^10\.11\.'; Engine = 'MariaDB' }
    'MARIADB_11_4'   = @{ Ver = '^11\.4\.'; Engine = 'MariaDB' }
}
$REPO = (Resolve-Path "$PSScriptRoot\..").Path
if (-not $Personas.ContainsKey($Persona)) {
    Write-Output ("ABORT(posture): 非法 persona '" + $Persona + "'，白名单=" + (($Personas.Keys | Sort-Object) -join ','))
    Write-Output '  -Dmycat.persona 拼错时 PersonaOverride 只记 WARN 并静默回退 ⇒ 会把这个画像测成另一个画像（假绿）'
    exit 1
}
$wantEngine = $Personas[$Persona].Engine
# --- JDK 门（本机器自身踩过：shell 的 JAVA_HOME 指向 LibericaJDK-25 时，Lombok 跑不动，
#     mvn 报一屏"找不到符号"（getTargetName/setLastInsertId/…全是 Lombok 生成的访问器），
#     看着像产品回归，其实是编译工具链错配。故此处硬要求 major=21，除非显式 -AllowAnyJdk。）
function Get-JdkMajor([string]$h) {
    if (-not (Test-Path "$h\bin\java.exe")) { return 0 }
    $v = (& "$h\bin\java.exe" -version 2>&1 | Select-Object -First 1) -join ''
    if ($v -match '"(\d+)(\.|$)') { return [int]$Matches[1] }
    return 0
}
$JdkCandidates = @($JdkHome, $env:JAVA_HOME, 'D:\Program Files\BellSoft\LibericaJDK-21-Full') | Where-Object { $_ } | Select-Object -Unique
$JdkFound = $null
foreach ($h in $JdkCandidates) { if ((Get-JdkMajor $h) -eq 21) { $JdkFound = $h; break } }
if (-not $JdkFound) {
    if ($AllowAnyJdk) { $JdkFound = $JdkCandidates[0] }
    else {
        Write-Output ("ABORT(jdk): 未找到 major=21 的 JDK（候选 " + ($JdkCandidates -join ' | ') +
            " 实得 " + (Get-JdkMajor $env:JAVA_HOME) + "）。用 -JdkHome <path> 指定，或 -AllowAnyJdk 明知而为之")
        exit 1
    }
}
$env:JAVA_HOME = $JdkFound
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Output ("JAVA_HOME=" + $env:JAVA_HOME + "  major=" + (Get-JdkMajor $env:JAVA_HOME))
if (-not (Test-Path $Jar)) { Write-Output "ABORT: 找不到 jar $Jar（先 mvn -o -B package -DskipTests）"; exit 1 }
if (-not $MycatHome) { $MycatHome = Join-Path (Split-Path $Jar) '.mycat-home' }   # MycatCore.findMycatHome 的回退口径
$dsJson = Join-Path $MycatHome "datasources\$DsName.datasource.json"
Write-Output ("REPO=" + $REPO + "  persona=" + $Persona + "  jar=" + (Split-Path $Jar -Leaf) +
    " mtime=" + (Get-Item $Jar).LastWriteTime + "  home=" + $MycatHome)
Write-Output ("GIT_HEAD=" + (git -C $REPO rev-parse --short HEAD) + "  dirtyFiles=" +
    ((git -C $REPO status --porcelain -- config server protocol router common va hbt datasource | Measure-Object).Count))
# jar 新鲜度提示（防"改了码没重打 jar 就验活体"——本仓铁律）
$srcRoots = 'config', 'server', 'common', 'protocol', 'router', 'hbt', 'va', 'datasource', 'replica', 'plug' |
ForEach-Object { Join-Path $REPO $_ }
$newestSrc = Get-ChildItem -Path $srcRoots -Recurse -Filter *.java -File -ErrorAction SilentlyContinue |
Where-Object { $_.FullName -notmatch '\\target\\' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($newestSrc -and $newestSrc.LastWriteTime -gt (Get-Item $Jar).LastWriteTime) {
    Write-Output ("WARN(jar-stale): 源码(" + $newestSrc.LastWriteTime + ") 比 jar(" + (Get-Item $Jar).LastWriteTime +
        ") 新 ⇒ 先 mvn -o -B package -DskipTests；最新文件 " + $newestSrc.FullName.Replace($REPO + '\', ''))
}

# --- 0) 后端容器就位 ---
docker start $BackendContainer 2>&1 | Out-Null
for ($i = 0; $i -lt 30; $i++) {
    docker exec $BackendContainer sh -c "mysqladmin ping -h127.0.0.1 -uroot -p123456 >/dev/null 2>&1" 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep 2
}
# 容器→宿主 的可达地址（Docker Desktop 优先 host.docker.internal，回落网关 IP）
function Invoke-HostSql([string]$port, [string]$sql) {
    foreach ($h in @('host.docker.internal', '192.168.65.254')) {
        $r = docker exec $BackendContainer sh -c "mysql --protocol=tcp -h$h -P$port -uroot -p123456 --get-server-public-key -N -e '$sql' 2>/dev/null"
        if ($LASTEXITCODE -eq 0 -and ($r -join '').Trim()) { return , @($h, ($r -join '').Trim()) }
    }
    return , @('', '')
}

# --- 1) 杀 8066 并用当前 jar + persona 重启 ---
if (-not $SkipRestart) {
    $conn = Get-NetTCPConnection -LocalPort 8066 -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conn) {
        Write-Output ("KILL 8066 pid " + $c.OwningProcess)
        Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    if ($conn) { Start-Sleep 3 }
    $scratch = "$REPO\target\ci-guard"
    New-Item -ItemType Directory -Force -Path $scratch | Out-Null
    $p = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -WorkingDirectory $REPO -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$scratch\proxy-$Persona.log" -RedirectStandardError "$scratch\proxy-$Persona.err.log" `
        -ArgumentList @('--add-opens=java.base/java.nio=ALL-UNNAMED', '-Duser.timezone=Asia/Shanghai',
        '-Dfile.encoding=UTF-8', "-Dmycat.persona=$Persona", '-jar', $Jar)
    Write-Output ("STARTED 8066 pid " + $p.Id)
}

# --- 2~4) 就绪 / posture gate / 跑测：整段包在 try 里，finally 保证"本轮起的 8066 绝不留给下一轮"
#          （本票的污染向量正是长命进程；中途 exit 也会走 finally，-KeepRunning 才放行留活）
try {
    # --- 2) 就绪：TCP 通 + 读到握手首字节 + settle ---
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $cli = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 8066)
            $s = $cli.GetStream(); $s.ReadTimeout = 5000
            $b = New-Object byte[] 5
            $n = $s.Read($b, 0, 5)
            $cli.Close()
            if ($n -ge 5 -and $b[0] -gt 0) { $ready = $true; break }
        } catch { Start-Sleep 2; continue }
        Start-Sleep 2
    }
    if (-not $ready) { Write-Output 'ABORT(readiness): 8066 未读到握手首字节'; exit 1 }
    Start-Sleep 15

    # --- 3) posture gate：前端画像 + 磁盘态后端引擎族，两条都过才准跑测 ---
    $front = Invoke-HostSql 8066 'select version()'
    if ($front[1] -notmatch $Personas[$Persona].Ver) {
        Write-Output ("ABORT(posture): 前端 version()='" + $front[1] + "' 不在 persona $Persona 宣称的族 " +
            $Personas[$Persona].Ver + "（经 " + $front[0] + " 探测）")
        exit 1
    }
    if (-not (Test-Path $dsJson)) { Write-Output "ABORT(posture): 磁盘态数据源配置不存在 $dsJson"; exit 1 }
    $dsUrl = ((Get-Content $dsJson -Raw) | ConvertFrom-Json).url
    $bp = [regex]::Match($dsUrl, '//([^/:]+):(\d+)')
    $backend = Invoke-HostSql $bp.Groups[2].Value 'select version()'
    $beEngine = if ($backend[1] -match 'MariaDB') { 'MariaDB' } elseif ($backend[1] -match '\d+\.\d+') { 'MySQL' } else { '' }
    Write-Output ("POSTURE front(8066)=" + $front[1] + " via " + $front[0] +
        " | back(" + $bp.Groups[1].Value + ":" + $bp.Groups[2].Value + ", disk " + $DsName + ", mtime " +
        (Get-Item $dsJson).LastWriteTime + ")=" + $backend[1] +
        " via " + $backend[0] + "  [persona 引擎族=" + $wantEngine + "]")
    if (-not $backend[1]) { Write-Output 'ABORT(posture): 读不到磁盘态后端版本（容器/端口不可达）'; exit 1 }
    if ($beEngine -ne $wantEngine) {
        Write-Output ("ABORT(posture): 后端引擎族=" + $beEngine + " 与 persona 引擎族=" + $wantEngine +
            " 不一致 ⇒ 用例期望值会连到与画像不符的后端（假红指纹），拒绝跑测")
        exit 1
    }
    if ($backend[1] -notmatch $Personas[$Persona].Ver) {
        Write-Output 'WARN(posture): 后端 minor 与 persona 宣称 minor 不同（属常见组合，仅提示不阻断）'
    }
    Write-Output 'POSTURE OK'
    if ($GateOnly) { Write-Output 'GATE_ONLY: 前置校验通过，未跑测试'; exit 0 }

    # --- 4) 全量护栏 ---
    $log = "$REPO\target\ci-guard\guard-$(Get-Date -Format yyyyMMdd-HHmmss).log"
    $mvnArgs = @('-o', '-B', '-pl', $Modules, '-am', 'test')
    if ($Test) {
        # 取证用：只跑指定类/方法，仍保留 persona 白名单门 + JDK 门 + 姿态门 + finally 收尾
        $mvnArgs += "-Dtest=$Test"
        $mvnArgs += '-Dsurefire.failIfNoSpecifiedTests=false'
    }
    Write-Output ("MVN " + ($mvnArgs -join ' ') + "  →  " + $log)
    mvn @mvnArgs *>&1 | Out-File -Encoding utf8 $log
    Write-Output ("MVN_EXIT=" + $LASTEXITCODE)
    Write-Output '=== 汇总（各模块）==='
    Get-Content $log | Select-String -Pattern '^\[(INFO|ERROR)\] Tests run:.*(Failures|Errors)' | ForEach-Object { $_.Line }
    Write-Output '=== 红类清单 ==='
    Get-Content $log | Select-String -Pattern '<<< (FAILURE|ERROR)!' | Select-Object -First 60 | ForEach-Object { $_.Line.Trim() }
    Write-Output '=== reactor 结论 ==='
    Get-Content $log | Select-String -Pattern 'BUILD (SUCCESS|FAILURE)' | Select-Object -First 5 | ForEach-Object { $_.Line.Trim() }
} finally {
    if (-not $KeepRunning) {
        Get-NetTCPConnection -LocalPort 8066 -State Listen -ErrorAction SilentlyContinue | ForEach-Object {
            Write-Output ("KILL 8066 pid " + $_.OwningProcess + "（收尾，防污染下一轮）")
            Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
        }
    } else {
        Write-Output 'KEEP_RUNNING: 按 -KeepRunning 保留 8066（下次务必带 -SkipRestart 或先手工杀）'
    }
}
