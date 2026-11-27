param(
    [Parameter(Mandatory = $true)][int]$ServerPid,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [string]$FrontPort = '8066',
    [string]$ConnectorJar = 'D:\Nexus\repository\com\mysql\mysql-connector-j\9.3.0\mysql-connector-j-9.3.0.jar',
    [string]$JdkHome = 'D:\Program Files\BellSoft\LibericaJDK-21-Full'
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
if (-not (Get-Process -Id $ServerPid -ErrorAction SilentlyContinue)) { throw "server process not found: $ServerPid" }
$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
$classes = Join-Path $EvidenceDir 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& $javac -encoding UTF-8 -cp $ConnectorJar -d $classes (Join-Path $PSScriptRoot 'FrontendCorrectnessProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'probe compilation failed' }

if ([string]::IsNullOrEmpty($env:MYSQL_PWD)) { throw 'MYSQL_PWD must be supplied by the caller' }
$summary = [System.Collections.Generic.List[string]]::new()
try {
    $javaOutput = & $java -cp "$classes;$ConnectorJar" FrontendCorrectnessProbe 127.0.0.1 $FrontPort 2>&1
    $javaExit = $LASTEXITCODE
    $javaOutput | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'java-probe.log')
    if ($javaExit -ne 0) { throw "java probe failed: $($javaOutput -join "`n")" }
    $summary.AddRange([string[]]$javaOutput)

    $pythonOutput = & python (Join-Path $PSScriptRoot 'verify-frontend-correctness.py') $FrontPort 2>&1
    $pythonExit = $LASTEXITCODE
    $pythonOutput | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'python-probe.log')
    if ($pythonExit -ne 0) { throw "python probe failed: $($pythonOutput -join "`n")" }
    $summary.AddRange([string[]]$pythonOutput)
} finally {
    $summary | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'summary.tsv')
}
if (($summary -join "`n") -notmatch 'verdict\tPASS') { exit 1 }
